package com.stockmarketanalyzer.backend.dividend.service;

import com.stockmarketanalyzer.backend.common.ResourceNotFoundException;
import com.stockmarketanalyzer.backend.common.Units;
import com.stockmarketanalyzer.backend.common.csv.CsvFile;
import com.stockmarketanalyzer.backend.common.csv.CsvImportResult;
import com.stockmarketanalyzer.backend.common.csv.CsvRow;
import com.stockmarketanalyzer.backend.common.csv.CsvRowException;
import com.stockmarketanalyzer.backend.common.csv.CsvWriter;
import com.stockmarketanalyzer.backend.dividend.domain.Dividend;
import com.stockmarketanalyzer.backend.dividend.repository.DividendRepository;
import com.stockmarketanalyzer.backend.dividend.repository.DividendSpecifications;
import com.stockmarketanalyzer.backend.dividend.web.dto.DividendRequest;
import com.stockmarketanalyzer.backend.inflation.service.InflationFactors;
import com.stockmarketanalyzer.backend.inflation.service.InflationRateService;
import com.stockmarketanalyzer.backend.instrument.domain.Instrument;
import com.stockmarketanalyzer.backend.instrument.repository.InstrumentRepository;
import com.stockmarketanalyzer.backend.security.CurrentUser;
import com.stockmarketanalyzer.backend.wealth.service.ExchangeRateService;
import com.stockmarketanalyzer.backend.wealth.service.WealthEntryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * CRUD over the dividends the owner has been paid, plus the same on-read valuation the wealth
 * slice uses: nothing about worth is persisted, so a corrected rate re-values the whole history.
 *
 * <p>It reuses {@link ExchangeRateService} across the slice boundary deliberately. The rates are
 * the <em>owner's own marks</em>, not wealth-specific data, and quoting them twice would mean a
 * dividend and a holding in the same currency on the same day could disagree about what it was
 * worth.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DividendService {

    private static final int VALUE_SCALE = 6;

    /** Columns a dividend CSV must carry, spelled as the sample writes them. */
    public static final List<String> CSV_COLUMNS = List.of("symbol", "amount", "unit", "paymentDate");

    /** The downloadable sample: the header, and one row showing what each column looks like. */
    public static final String CSV_SAMPLE =
            """
            symbol,amount,unit,paymentDate
            AAPL,42.75,USD,2026-08-15
            """;

    private final DividendRepository repository;
    private final InstrumentRepository instruments;
    private final ExchangeRateService exchangeRates;
    private final CurrentUser currentUser;
    private final InflationRateService inflationRates;

    /**
     * The sort property that means "the converted value". It is not a column -- it is worked out
     * from the rates per row -- so the database cannot order by it and {@link #searchValued} does.
     */
    public static final String VALUE_SORT = "value";

    public Page<Dividend> search(
            LocalDate from, LocalDate to, Long instrumentId, String unit, Pageable pageable) {
        return repository.findAll(specification(from, to, instrumentId, unit), pageable);
    }

    /** The sort property for the amount column, which an inflation-adjusted sort must redo in memory. */
    public static final String AMOUNT_SORT = "amount";

    /**
     * A page of dividends together with each row's value in the requested unit, and -- only when
     * asked for -- each row's inflation-adjusted figures (otherwise {@code adjusted} is empty).
     */
    public record ValuedPage(Page<Dividend> page, Map<Long, BigDecimal> values, Map<Long, Adjusted> adjusted) {}

    /**
     * One row's figures in the money of the latest year with an inflation figure.
     *
     * @param amount {@code amount} adjusted with the inflation of the currency it was paid in; the
     *     amount as entered when that cannot be computed
     * @param value the converted value adjusted with the value currency's inflation -- converted at
     *     the payment date's rate <em>first</em>, then adjusted; {@code null} exactly when the
     *     unadjusted value is (no exchange rate)
     * @param complete false when either figure had to be left unadjusted (no inflation data for the
     *     currency, a year older than the series, or a gap)
     */
    public record Adjusted(BigDecimal amount, BigDecimal value, boolean complete) {}

    /**
     * The list endpoint's read: a page, valued in {@code targetUnit}.
     *
     * <p>Every ordinary sort is the database's, and only the page is valued. <strong>Sorting by
     * {@value #VALUE_SORT} cannot work that way</strong>: the value is the amount times the rate in
     * force on the payment date (personal rate first, global otherwise), which no SQL column holds.
     * So that one sort loads <em>every</em> row the filter matches, values them all -- one rate
     * lookup per distinct (currency, date), memoised -- sorts in memory and cuts the page out.
     * That is what makes page 2 continue where page 1 stopped; sorting only the rows on screen
     * would not. It is affordable because dividends are one person's own history -- hundreds or a
     * few thousand rows, which {@link #total} already reads in full for the footer.
     *
     * <p>Rows with no rate (value {@code null}) go last in both directions, matching how the tables
     * order missing values. Ties fall back to newest payment first, then id, so the order is stable
     * across requests and a row cannot appear on two pages.
     */
    public ValuedPage searchValued(
            LocalDate from,
            LocalDate to,
            Long instrumentId,
            String unit,
            String targetUnit,
            boolean inflationAdjusted,
            Pageable pageable) {
        Sort.Order byValue = pageable.getSort().getOrderFor(VALUE_SORT);
        // With the adjustment on, the amount the table shows is no longer the stored column either,
        // so sorting by it has to be done in memory over the adjusted figures, like the value.
        Sort.Order byAdjustedAmount = inflationAdjusted ? pageable.getSort().getOrderFor(AMOUNT_SORT) : null;
        InflationFactors factors = inflationAdjusted ? inflationRates.factors() : null;

        if (byValue == null && byAdjustedAmount == null) {
            Page<Dividend> page = search(from, to, instrumentId, unit, pageable);
            Map<Long, BigDecimal> values = valuesIn(page.getContent(), targetUnit);
            return new ValuedPage(page, values, adjust(page.getContent(), values, targetUnit, factors));
        }

        List<Dividend> all = new ArrayList<>(repository.findAll(specification(from, to, instrumentId, unit)));
        Map<Long, BigDecimal> values = valuesIn(all, targetUnit);
        Map<Long, Adjusted> adjusted = adjust(all, values, targetUnit, factors);

        Sort.Order order = byValue != null ? byValue : byAdjustedAmount;
        Comparator<BigDecimal> direction =
                order.isAscending() ? Comparator.naturalOrder() : Comparator.reverseOrder();
        Function<Dividend, BigDecimal> key;
        if (byValue == null) {
            key = dividend -> adjusted.get(dividend.getId()).amount();
        } else if (inflationAdjusted) {
            key = dividend -> adjusted.get(dividend.getId()).value();
        } else {
            key = dividend -> values.get(dividend.getId());
        }
        all.sort(Comparator.comparing(key, Comparator.nullsLast(direction))
                .thenComparing(Dividend::getPaymentDate, Comparator.reverseOrder())
                .thenComparing(Dividend::getId, Comparator.reverseOrder()));

        int start = (int) Math.min(pageable.getOffset(), all.size());
        int end = Math.min(start + pageable.getPageSize(), all.size());
        Page<Dividend> page = new PageImpl<>(List.copyOf(all.subList(start, end)), pageable, all.size());
        return new ValuedPage(page, values, adjusted);
    }

    /**
     * The inflation-adjusted figures of each row, or an empty map when {@code factors} is null (the
     * adjustment was not asked for). Same rule as the monthly grid's {@code adjustPayments}: the
     * amount with its own currency's inflation, the already-converted value with the value
     * currency's; a figure that cannot be adjusted is kept as it is and the row marked incomplete.
     */
    private static Map<Long, Adjusted> adjust(
            Collection<Dividend> dividends, Map<Long, BigDecimal> values, String targetUnit, InflationFactors factors) {
        if (factors == null) {
            return Map.of();
        }
        String target = WealthEntryService.resolveValueUnit(targetUnit);
        Map<Long, Adjusted> adjusted = new HashMap<>();
        for (Dividend dividend : dividends) {
            int year = dividend.getPaymentDate().getYear();
            BigDecimal amountFactor = factors.factor(dividend.getUnit(), year);
            BigDecimal amount = amountFactor == null
                    ? dividend.getAmount()
                    : dividend.getAmount().multiply(amountFactor).setScale(VALUE_SCALE, RoundingMode.HALF_UP);

            BigDecimal value = values.get(dividend.getId());
            boolean valueAdjusted = true;
            if (value != null) {
                BigDecimal valueFactor = factors.factor(target, year);
                if (valueFactor == null) {
                    valueAdjusted = false;
                } else {
                    value = value.multiply(valueFactor).setScale(VALUE_SCALE, RoundingMode.HALF_UP);
                }
            }
            adjusted.put(dividend.getId(), new Adjusted(amount, value, amountFactor != null && valueAdjusted));
        }
        return adjusted;
    }

    public Dividend get(Long id) {
        return repository
                .findByIdAndOwnerSub(id, currentUser.requireSubject())
                .orElseThrow(() -> new ResourceNotFoundException("No dividend found with id %d".formatted(id)));
    }

    @Transactional
    public Dividend create(DividendRequest request) {
        String ownerSub = currentUser.requireSubject();
        Dividend dividend = new Dividend(
                ownerSub,
                resolveInstrument(request.instrumentId()),
                request.amount(),
                request.unit(),
                request.paymentDate() != null ? request.paymentDate() : LocalDate.now());
        Dividend saved = repository.save(dividend);
        log.debug(
                "Recorded dividend {} ({} {} from {})",
                saved.getId(),
                saved.getAmount(),
                saved.getUnit(),
                saved.getInstrument().getSymbol());
        return saved;
    }

    @Transactional
    public Dividend update(Long id, DividendRequest request) {
        Dividend dividend = get(id);
        dividend.setInstrument(resolveInstrument(request.instrumentId()));
        dividend.setAmount(request.amount());
        dividend.setUnit(request.unit());
        if (request.paymentDate() != null) {
            dividend.setPaymentDate(request.paymentDate());
        }
        return repository.save(dividend);
    }

    /**
     * Records a whole CSV file of payments at once.
     *
     * <p>The company is named by its ticker rather than by database id -- a file somebody fills
     * in by hand should not need them to look ids up first -- so an unknown symbol is a row that
     * fails, not a row that invents an instrument. Symbols are resolved once each and remembered
     * for the rest of the file, misses included, so a hundred payments from the same company cost
     * one query.
     *
     * <p>Unlike an exchange rate, a dividend has no natural key: nothing stops the same payment
     * being recorded twice, and re-uploading last month's file would otherwise double the
     * history. A row matching an existing one on all four fields is therefore counted as a
     * duplicate and skipped -- reported separately from a failure, because a duplicate is the
     * expected outcome of an overlapping upload rather than a mistake to go and fix.
     *
     * <p>Rows already written by this same upload are held in memory as well as looked up, so the
     * behaviour does not depend on when the persistence context happens to flush.
     */
    @Transactional
    public CsvImportResult importCsv(MultipartFile file) {
        String ownerSub = currentUser.requireSubject();
        CsvFile csv = CsvFile.read(file, CSV_COLUMNS);
        LocalDate today = LocalDate.now();
        Map<String, Optional<Instrument>> resolved = new HashMap<>();
        Set<DividendKey> written = new HashSet<>();
        int processed = 0;
        int failed = 0;
        int duplicates = 0;

        for (CsvRow row : csv.rows()) {
            try {
                String symbol = Instrument.normaliseSymbol(row.requireText("symbol"));
                Instrument instrument = resolved.computeIfAbsent(symbol, instruments::findBySymbol)
                        .orElseThrow(() -> new CsvRowException(
                                row.lineNumber(), "no instrument has the symbol '%s'".formatted(symbol)));
                BigDecimal amount = row.requireNonNegative("amount");
                String unit = Units.normalise(row.requireText("unit"));
                LocalDate paymentDate = row.dateOr("paymentDate", today);

                DividendKey key = new DividendKey(
                        instrument.getId(), amount.stripTrailingZeros(), unit, paymentDate);
                if (!written.add(key)
                        || repository.existsByOwnerSubAndInstrumentIdAndAmountAndUnitAndPaymentDate(
                                ownerSub, instrument.getId(), amount, unit, paymentDate)) {
                    duplicates++;
                    continue;
                }

                repository.save(new Dividend(ownerSub, instrument, amount, unit, paymentDate));
                processed++;
            } catch (CsvRowException ex) {
                failed++;
                log.debug("Skipped dividend CSV row: {}", ex.getMessage());
            }
        }

        log.info("Imported {} of {} dividend rows ({} duplicates)", processed, csv.rows().size(), duplicates);
        return new CsvImportResult(csv.rows().size(), processed, failed, duplicates);
    }

    /**
     * Every payment the owner has recorded, written out in the shape the import reads.
     *
     * <p>The company is named by its ticker, exactly as the import expects it, so the file means
     * the same thing in another account -- an instrument id would not. The list endpoint's filters
     * are deliberately not applied: this is the whole of the owner's own data, which is what makes
     * it usable as a backup.
     */
    public String exportCsv() {
        List<Dividend> dividends =
                repository.findAll(specification(null, null, null, null), Sort.by("paymentDate", "instrument.symbol"));
        CsvWriter csv = new CsvWriter(CSV_COLUMNS);
        for (Dividend dividend : dividends) {
            csv.row(
                    dividend.getInstrument().getSymbol(),
                    dividend.getAmount(),
                    dividend.getUnit(),
                    dividend.getPaymentDate());
        }
        log.debug("Exported {} dividend rows", dividends.size());
        return csv.toCsv();
    }

    @Transactional
    public void delete(Long id) {
        Dividend dividend = get(id);
        repository.delete(dividend);
        log.info("Deleted dividend {}", dividend.getId());
    }

    /**
     * Sums everything the filter matches, not just the page being shown -- a total that changed
     * when you turned the page would be worse than no total at all.
     */
    public DividendTotal total(
            LocalDate from, LocalDate to, Long instrumentId, String unit, String targetUnit, boolean inflationAdjusted) {
        List<Dividend> matching = repository.findAll(specification(from, to, instrumentId, unit));
        if (matching.isEmpty()) {
            return DividendTotal.EMPTY;
        }

        Map<Long, BigDecimal> values = valuesIn(matching, targetUnit);
        Map<Long, Adjusted> adjusted =
                adjust(matching, values, targetUnit, inflationAdjusted ? inflationRates.factors() : null);
        BigDecimal total = null;
        boolean partial = false;
        long notAdjusted = 0;
        for (Dividend dividend : matching) {
            Adjusted row = adjusted.get(dividend.getId());
            BigDecimal value = row == null ? values.get(dividend.getId()) : row.value();
            if (row != null && !row.complete()) {
                notAdjusted++;
            }
            if (value == null) {
                partial = true;
            } else {
                total = total == null ? value : total.add(value);
            }
        }
        return new DividendTotal(matching.size(), total, partial, notAdjusted);
    }

    /**
     * Values each dividend in {@code targetUnit} using the rate in force on its own payment
     * date. A unit the owner has never quoted maps to {@code null} rather than zero -- "not
     * convertible" and "worth nothing" are different answers, as everywhere else here.
     *
     * <p>Factors are memoised per (unit, date), so a page of rows sharing a currency costs one
     * rate lookup rather than one per row.
     */
    public Map<Long, BigDecimal> valuesIn(Collection<Dividend> dividends, String targetUnit) {
        String target = WealthEntryService.resolveValueUnit(targetUnit);
        String ownerSub = currentUser.requireSubject();
        Map<FactorKey, Optional<BigDecimal>> cache = new HashMap<>();
        Map<Long, BigDecimal> values = new LinkedHashMap<>();

        for (Dividend dividend : dividends) {
            Optional<BigDecimal> factor = cache.computeIfAbsent(
                    new FactorKey(dividend.getUnit(), dividend.getPaymentDate()),
                    key -> exchangeRates.resolveFactor(ownerSub, key.unit(), target, key.date()));
            values.put(
                    dividend.getId(),
                    factor.map(value -> dividend.getAmount()
                                    .multiply(value)
                                    .setScale(VALUE_SCALE, RoundingMode.HALF_UP))
                            .orElse(null));
        }
        return values;
    }

    private Specification<Dividend> specification(LocalDate from, LocalDate to, Long instrumentId, String unit) {
        return DividendSpecifications.dividends(currentUser.requireSubject(), from, to, instrumentId, unit);
    }

    /**
     * Instruments are shared reference data, so this is not owner-scoped -- but it still 404s
     * on an unknown id rather than storing a dangling reference the FK would reject later.
     */
    private Instrument resolveInstrument(Long instrumentId) {
        return instruments
                .findById(instrumentId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("No instrument found with id %d".formatted(instrumentId)));
    }

    /** Cache key for a resolved conversion factor within one call. */
    private record FactorKey(String unit, LocalDate date) {}

    /**
     * What "the same dividend" means, for the import's duplicate check. The amount is stripped of
     * trailing zeros so 42.75 and 42.750 are one key, matching how the database compares numerics.
     */
    private record DividendKey(Long instrumentId, BigDecimal amount, String unit, LocalDate paymentDate) {}
}
