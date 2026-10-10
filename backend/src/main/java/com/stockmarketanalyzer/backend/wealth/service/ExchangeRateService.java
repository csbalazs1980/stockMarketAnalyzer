package com.stockmarketanalyzer.backend.wealth.service;

import com.stockmarketanalyzer.backend.common.ResourceNotFoundException;
import com.stockmarketanalyzer.backend.common.Units;
import com.stockmarketanalyzer.backend.common.csv.CsvFile;
import com.stockmarketanalyzer.backend.common.csv.CsvImportResult;
import com.stockmarketanalyzer.backend.common.csv.CsvRow;
import com.stockmarketanalyzer.backend.common.csv.CsvRowException;
import com.stockmarketanalyzer.backend.common.csv.CsvWriter;
import com.stockmarketanalyzer.backend.security.CurrentUser;
import com.stockmarketanalyzer.backend.wealth.domain.ExchangeRate;
import com.stockmarketanalyzer.backend.wealth.repository.ExchangeRateRepository;
import com.stockmarketanalyzer.backend.wealth.repository.WealthSpecifications;
import com.stockmarketanalyzer.backend.wealth.web.dto.ExchangeRateRequest;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stores conversion factors -- one at a time or a CSV file at a time -- and answers "what was one
 * X worth in Y on this date".
 *
 * <p>Two kinds of rate, as with expense categories: a caller's <em>personal</em> rates and the
 * <em>global</em> ones (no owner) that everybody is valued from. Everybody reads both; only an
 * administrator writes global ones. The check lives here rather than in {@code SecurityConfig}
 * because {@code /api/v1/wealth/**} has to stay open to every signed-in user for their own rows --
 * "global" is a property of the payload, which only the service can see.
 *
 * <p><strong>A personal rate always wins.</strong> If the caller has quoted a pair at all (on or
 * before the date in question), that is the rate used, even when a newer global one exists: a
 * personal rate is a deliberate override, and it would be surprising for it to stop applying
 * because somebody refreshed the shared feed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExchangeRateService {

    /** Enough headroom that chained/inverted rates do not lose meaningful precision. */
    private static final MathContext INVERSION_PRECISION = new MathContext(20, RoundingMode.HALF_UP);

    /** Columns a rate CSV must carry, spelled as the sample writes them. */
    public static final List<String> CSV_COLUMNS = List.of("sourceUnit", "destinationUnit", "rate", "rateDate");

    /** The downloadable sample: the header, and one row showing what each column looks like. */
    public static final String CSV_SAMPLE =
            """
            sourceUnit,destinationUnit,rate,rateDate
            USD,HUF,352.4,2026-09-01
            """;

    private final ExchangeRateRepository repository;
    private final CurrentUser currentUser;

    /**
     * The caller's rates and the global ones, filtered.
     *
     * @param global {@code null} for both kinds, {@code true} for global only, {@code false} for
     *     the caller's own only
     */
    public Page<ExchangeRate> search(
            Boolean global,
            String sourceUnit,
            String destinationUnit,
            LocalDate from,
            LocalDate to,
            Pageable pageable) {
        return repository.findAll(
                WealthSpecifications.rates(
                        currentUser.requireSubject(), global, sourceUnit, destinationUnit, from, to),
                pageable);
    }

    /** One of the caller's rates, or a global one. Somebody else's is a 404, never a 403. */
    public ExchangeRate get(Long id) {
        return repository
                .findByIdAndOwnerSub(id, currentUser.requireSubject())
                .or(() -> repository.findByIdAndOwnerSubIsNull(id))
                .orElseThrow(() -> notFound(id));
    }

    /**
     * The quote valuation would use for this pair on {@code on}: the caller's own if they have one,
     * otherwise the global one. Direct pair only -- the reciprocal step is {@link #resolveFactor}'s.
     */
    public Optional<ExchangeRate> findInForce(String sourceUnit, String destinationUnit, LocalDate on) {
        String ownerSub = currentUser.requireSubject();
        String source = Units.normalise(sourceUnit);
        String destination = Units.normalise(destinationUnit);
        return personalInForce(ownerSub, source, destination, on)
                .or(() -> globalInForce(source, destination, on));
    }

    /**
     * The newest quote for each pair, whatever its date: the caller's own where they have quoted
     * the pair, the global one for every other pair.
     *
     * <p>Deliberately date-less, unlike {@link #findInForce}: it answers "the most recent rate
     * available", which is the only thing a figure carrying no date of its own -- a recurring
     * expense, say -- can be converted with. Personal wins per pair, as everywhere else.
     */
    public List<ExchangeRate> latestPerPair() {
        Map<String, ExchangeRate> byPair = new TreeMap<>();
        for (ExchangeRate rate : repository.findLatestGlobalPerPair()) {
            byPair.put(pairKey(rate), rate);
        }
        for (ExchangeRate rate : repository.findLatestPerPair(currentUser.requireSubject())) {
            byPair.put(pairKey(rate), rate);
        }
        return new ArrayList<>(byPair.values());
    }

    /**
     * Resolves a factor for {@code source -> destination} as of a date.
     *
     * <p>The same unit converts one-to-one. Otherwise the owner's <em>personal</em> rates are tried
     * first -- the directly quoted pair, then the reciprocal of the opposite quote -- and only when
     * neither exists are the <em>global</em> rates tried in the same two steps. That ordering is
     * what "a personal rate always wins" means: a user who quoted HUF to USD by hand is valued from
     * that, not from a global USD to HUF that happens to be the other way round. Empty when nobody
     * has quoted the pair either way.
     */
    public Optional<BigDecimal> resolveFactor(
            String ownerSub, String sourceUnit, String destinationUnit, LocalDate on) {
        String source = Units.normalise(sourceUnit);
        String destination = Units.normalise(destinationUnit);
        if (source == null || destination == null) {
            return Optional.empty();
        }
        if (source.equals(destination)) {
            return Optional.of(BigDecimal.ONE);
        }
        return factor(personalInForce(ownerSub, source, destination, on),
                        () -> personalInForce(ownerSub, destination, source, on))
                .or(() -> factor(globalInForce(source, destination, on),
                        () -> globalInForce(destination, source, on)));
    }

    /**
     * Inserts the quote, or replaces the one already stored for the same pair and date (within the
     * same kind), so re-posting a month's rates is idempotent. A global request needs the ADMIN
     * role. A personal rate for a pair and date that also has a global quote is allowed -- that is
     * exactly what overriding the shared rate looks like.
     */
    @Transactional
    public ExchangeRate upsert(ExchangeRateRequest request) {
        String ownerSub = request.isGlobal() ? null : currentUser.requireSubject();
        if (request.isGlobal()) {
            requireAdmin("store a global exchange rate");
        }
        return store(
                ownerSub,
                Units.normalise(request.sourceUnit()),
                Units.normalise(request.destinationUnit()),
                request.rate(),
                request.rateDate() != null ? request.rateDate() : LocalDate.now());
    }

    /**
     * Stores a quote as a global rate with no check of who is asking. For the rate feed, which has
     * already decided the caller may do this -- and for a scheduled job, which has no caller at all.
     * Package-private so nothing outside the slice can skip the check by accident.
     */
    @Transactional
    ExchangeRate storeGlobal(String sourceUnit, String destinationUnit, BigDecimal rate, LocalDate rateDate) {
        return store(
                null,
                Units.normalise(sourceUnit),
                Units.normalise(destinationUnit),
                rate,
                rateDate);
    }

    /** Refuses unless the caller holds the ADMIN role. Shared with the rate feed. */
    void requireAdmin(String action) {
        if (!currentUser.isAdmin()) {
            throw new AccessDeniedException("Only an administrator may %s.".formatted(action));
        }
    }

    /**
     * Stores a whole CSV file of quotes, one row at a time.
     *
     * <p>A row that cannot be read is counted and skipped rather than failing the upload: the
     * alternative is that one mistyped cell in a year of rates means none of them are stored.
     * Because a bad row never reaches the database, the single transaction around the import
     * still commits every good row -- there is nothing half-written to roll back.
     *
     * <p>There is no duplicate count here: {@link #store} is an upsert on (pair, date), so
     * re-uploading a file overwrites what it stored last time instead of adding to it.
     */
    @Transactional
    public CsvImportResult importCsv(MultipartFile file, boolean global) {
        // Asked once for the whole file, as the category import does, rather than per row: a
        // 'global' column would have to say what a non-administrator's 'true' means.
        if (global) {
            requireAdmin("import global exchange rates");
        }
        String ownerSub = global ? null : currentUser.requireSubject();
        CsvFile csv = CsvFile.read(file, CSV_COLUMNS);
        LocalDate today = LocalDate.now();
        int processed = 0;
        int failed = 0;

        for (CsvRow row : csv.rows()) {
            try {
                store(
                        ownerSub,
                        Units.normalise(row.requireText("sourceUnit")),
                        Units.normalise(row.requireText("destinationUnit")),
                        row.requirePositive("rate"),
                        row.dateOr("rateDate", today));
                processed++;
            } catch (CsvRowException | IllegalArgumentException ex) {
                failed++;
                log.debug("Skipped exchange-rate CSV row: {}", ex.getMessage());
            }
        }

        log.info("Imported {} of {} exchange-rate rows (global={})", processed, csv.rows().size(), global);
        return new CsvImportResult(csv.rows().size(), processed, failed, 0);
    }

    /**
     * Every quote the owner holds, written out in the shape the import reads.
     *
     * <p>The screen's filters are deliberately not applied. This is "download my data", and the
     * obvious thing to do with the file is keep it as a backup -- one that quietly left out the
     * rows the table happened to be hiding would be worse than no backup at all.
     *
     * <p><strong>Global rates are left out</strong>, although the screen shows them. They belong to
     * everybody rather than to the caller, and importing the file again would turn each of them into
     * a personal copy -- which, since personal always wins, would freeze that user's rates.
     * Same decision as the expense category export.
     *
     * <p>Ordered by date and then by pair so two exports of the same data are the same file, which
     * is what makes it worth diffing one against the last.
     */
    public String exportCsv() {
        List<ExchangeRate> rates = repository.findAll(
                WealthSpecifications.rates(currentUser.requireSubject(), false, null, null, null, null),
                Sort.by("rateDate", "sourceUnit", "destinationUnit"));
        CsvWriter csv = new CsvWriter(CSV_COLUMNS);
        for (ExchangeRate rate : rates) {
            csv.row(rate.getSourceUnit(), rate.getDestinationUnit(), rate.getRate(), rate.getRateDate());
        }
        log.debug("Exported {} exchange-rate rows", rates.size());
        return csv.toCsv();
    }

    /**
     * Inserts the quote, or replaces the one already stored for the same pair and date, so
     * re-posting a month's rates is idempotent. Shared by the single-row API and the CSV import
     * so the two cannot drift on what a valid quote is.
     */
    private ExchangeRate store(
            String ownerSub, String source, String destination, BigDecimal value, LocalDate rateDate) {
        if (source.equals(destination)) {
            throw new IllegalArgumentException(
                    "sourceUnit and destinationUnit must differ (both were '%s')".formatted(source));
        }
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("rate must be greater than zero");
        }

        Optional<ExchangeRate> existing = ownerSub == null
                ? repository.findByOwnerSubIsNullAndSourceUnitAndDestinationUnitAndRateDate(
                        source, destination, rateDate)
                : repository.findByOwnerSubAndSourceUnitAndDestinationUnitAndRateDate(
                        ownerSub, source, destination, rateDate);
        ExchangeRate rate =
                existing.orElseGet(() -> new ExchangeRate(ownerSub, source, destination, value, rateDate));
        rate.setRate(value);
        ExchangeRate saved = repository.save(rate);
        log.debug(
                "Stored {} rate 1 {} = {} {} on {}",
                ownerSub == null ? "global" : "personal",
                source,
                saved.getRate(),
                destination,
                rateDate);
        return saved;
    }

    /** The caller's own rate, or -- for an administrator -- a global one. */
    @Transactional
    public void delete(Long id) {
        String ownerSub = currentUser.requireSubject();
        Optional<ExchangeRate> own = repository.findByIdAndOwnerSub(id, ownerSub);
        if (own.isPresent()) {
            repository.delete(own.get());
            return;
        }
        ExchangeRate global = repository.findByIdAndOwnerSubIsNull(id).orElseThrow(() -> notFound(id));
        requireAdmin("delete a global exchange rate");
        repository.delete(global);
    }

    private Optional<ExchangeRate> personalInForce(
            String ownerSub, String source, String destination, LocalDate on) {
        return repository.findFirstByOwnerSubAndSourceUnitAndDestinationUnitAndRateDateLessThanEqualOrderByRateDateDesc(
                ownerSub, source, destination, on);
    }

    private Optional<ExchangeRate> globalInForce(String source, String destination, LocalDate on) {
        return repository
                .findFirstByOwnerSubIsNullAndSourceUnitAndDestinationUnitAndRateDateLessThanEqualOrderByRateDateDesc(
                        source, destination, on);
    }

    /** A direct quote's rate, else the reciprocal of the opposite quote (looked up only if needed). */
    private static Optional<BigDecimal> factor(
            Optional<ExchangeRate> direct, Supplier<Optional<ExchangeRate>> opposite) {
        if (direct.isPresent()) {
            return direct.map(ExchangeRate::getRate);
        }
        return opposite.get()
                .map(ExchangeRate::getRate)
                .filter(rate -> rate.signum() != 0)
                .map(rate -> BigDecimal.ONE.divide(rate, INVERSION_PRECISION));
    }

    private static String pairKey(ExchangeRate rate) {
        return rate.getSourceUnit() + ":" + rate.getDestinationUnit();
    }

    private static ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("No exchange rate found with id %d".formatted(id));
    }
}
