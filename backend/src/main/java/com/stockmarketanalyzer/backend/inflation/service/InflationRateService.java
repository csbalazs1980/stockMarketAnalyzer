package com.stockmarketanalyzer.backend.inflation.service;

import com.stockmarketanalyzer.backend.common.ResourceConflictException;
import com.stockmarketanalyzer.backend.common.ResourceNotFoundException;
import com.stockmarketanalyzer.backend.common.Units;
import com.stockmarketanalyzer.backend.common.csv.CsvFile;
import com.stockmarketanalyzer.backend.common.csv.CsvImportResult;
import com.stockmarketanalyzer.backend.common.csv.CsvRow;
import com.stockmarketanalyzer.backend.common.csv.CsvRowException;
import com.stockmarketanalyzer.backend.common.csv.CsvWriter;
import com.stockmarketanalyzer.backend.inflation.domain.InflationRate;
import com.stockmarketanalyzer.backend.inflation.repository.InflationRateRepository;
import com.stockmarketanalyzer.backend.inflation.repository.InflationRateSpecifications;
import com.stockmarketanalyzer.backend.inflation.web.dto.InflationRateRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InflationRateService {

    /** The columns an inflation CSV carries, in the order the sample and the export write them. All required. */
    public static final List<String> CSV_COLUMNS = List.of("unit", "year", "rate");

    /**
     * The downloadable sample. Real published 2024 annual averages from the same source the fetch
     * button reads (Eurostat HICP, {@code prc_hicp_ainr}: geo HU, EA and US), so a sample uploaded
     * as it is agrees with what a fetch would have stored.
     */
    public static final String CSV_SAMPLE =
            """
            unit,year,rate
            HUF,2024,3.7
            EUR,2024,2.4
            USD,2024,2.0
            """;

    /** {@code inflation_rate.rate} is {@code NUMERIC(9, 4)}. */
    private static final int RATE_SCALE = 4;

    private final InflationRateRepository repository;
    private final Validator validator;

    public Page<InflationRate> search(String unit, Integer fromYear, Integer toYear, Pageable pageable) {
        return repository.findAll(InflationRateSpecifications.filter(unit, fromYear, toYear), pageable);
    }

    /** Every currency with at least one stored figure, for the administration screen's filter. */
    public List<String> units() {
        return repository.findDistinctUnits();
    }

    /**
     * A snapshot of every stored figure as cumulative factors, for screens that show amounts in
     * today's money. Readable by any user -- these are published statistics.
     */
    public InflationFactors factors() {
        return InflationFactors.of(repository.findAll());
    }

    public InflationRate get(Long id) {
        return repository.findById(id).orElseThrow(() -> notFound(id));
    }

    public InflationRate getByUnitAndYear(String unit, Integer year) {
        String normalised = Units.normalise(unit);
        return repository
                .findByUnitAndYear(normalised, year)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No %s inflation rate recorded for %d".formatted(normalised, year)));
    }

    /**
     * Inserts the figure, or replaces the one already stored for the same currency and year.
     * Idempotent on purpose: a first estimate gets superseded by the final print, and re-posting
     * a series should not require checking what is already there.
     */
    @Transactional
    public InflationRate upsert(InflationRateRequest request) {
        String unit = Units.normalise(request.unit());
        BigDecimal rate = scaled(request.rate());
        InflationRate stored = repository
                .findByUnitAndYear(unit, request.year())
                .orElseGet(() -> new InflationRate(unit, request.year(), rate));
        stored.setRate(rate);
        InflationRate saved = repository.save(stored);
        log.info("Stored {} inflation for {}: {}%", saved.getUnit(), saved.getYear(), saved.getRate());
        return saved;
    }

    /**
     * Replaces one row, addressed by id. Unlike {@link #upsert} the currency and the year may
     * change too -- a figure typed under the wrong year is corrected here, not deleted and
     * re-entered. Moving it onto a (currency, year) that already has a figure is refused rather
     * than silently overwriting the other row.
     */
    @Transactional
    public InflationRate update(Long id, InflationRateRequest request) {
        InflationRate stored = get(id);
        String unit = Units.normalise(request.unit());
        repository
                .findByUnitAndYear(unit, request.year())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ResourceConflictException(
                            "%s inflation for %d is already recorded".formatted(unit, request.year()));
                });
        stored.setUnit(unit);
        stored.setYear(request.year());
        stored.setRate(scaled(request.rate()));
        log.info("Updated inflation rate {}: {} {} {}%", id, stored.getUnit(), stored.getYear(), stored.getRate());
        return repository.save(stored);
    }

    /** What {@link #storeNewYears} did: the latest year held before, and the rows it added. */
    public record NewYears(Integer previousLatestYear, List<InflationRate> stored) {}

    /**
     * Stores the years of a fetched series that are newer than anything already held for the
     * currency -- every year when nothing is held yet. Years at or before the latest stored one are
     * left alone, including ones the source has since revised: a figure an administrator typed or
     * corrected is never overwritten by a fetch.
     *
     * <p>The latest year is read inside this transaction, so two presses at once cannot both decide
     * the same year is missing (the unique constraint would refuse the second insert anyway).
     */
    @Transactional
    public NewYears storeNewYears(String unit, SortedMap<Integer, BigDecimal> series) {
        String normalised = Units.normalise(unit);
        Integer latest = repository.findLatestYear(normalised).orElse(null);
        SortedMap<Integer, BigDecimal> missing = latest == null ? series : series.tailMap(latest + 1);
        List<InflationRate> stored = new ArrayList<>();
        for (var entry : missing.entrySet()) {
            stored.add(repository.save(new InflationRate(normalised, entry.getKey(), scaled(entry.getValue()))));
        }
        if (!stored.isEmpty()) {
            log.info(
                    "Stored {} fetched {} inflation year(s): {}-{}",
                    stored.size(),
                    normalised,
                    missing.firstKey(),
                    missing.lastKey());
        }
        return new NewYears(latest, stored);
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(get(id));
        log.info("Deleted inflation rate {}", id);
    }

    /**
     * Stores a whole CSV file of figures, matched on (currency, year): a pair already stored is
     * updated from its row, a new one is created -- the same upsert as {@code POST}, so
     * re-uploading an edited export applies the edits.
     *
     * <p>Every row goes through the API's own bean-validation rules, so the file and the form
     * cannot drift on what a valid figure is. A row that fails is counted and skipped rather than
     * failing the file; a row identical to what is stored is counted as a duplicate and leaves the
     * row's {@code updated_at} alone. When the same (currency, year) appears twice in one file,
     * the later row wins, as it would if the rows were posted one by one.
     *
     * <p>The table is loaded once into a map: it is a handful of currencies times a few decades.
     */
    @Transactional
    public CsvImportResult importCsv(MultipartFile file) {
        CsvFile csv = CsvFile.read(file, CSV_COLUMNS);
        Map<String, InflationRate> byKey = new HashMap<>();
        for (InflationRate rate : repository.findAll()) {
            byKey.put(key(rate.getUnit(), rate.getYear()), rate);
        }

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int failed = 0;
        for (CsvRow row : csv.rows()) {
            try {
                InflationRateRequest request = toRequest(row);
                String unit = Units.normalise(request.unit());
                BigDecimal rate = scaled(request.rate());
                InflationRate existing = byKey.get(key(unit, request.year()));
                if (existing == null) {
                    InflationRate saved = repository.save(new InflationRate(unit, request.year(), rate));
                    byKey.put(key(unit, request.year()), saved);
                    created++;
                } else if (existing.getRate().compareTo(rate) != 0) {
                    // Managed entity: the change is flushed at commit, no save() needed.
                    existing.setRate(rate);
                    updated++;
                } else {
                    unchanged++;
                }
            } catch (CsvRowException | IllegalArgumentException ex) {
                failed++;
                log.debug("Skipped inflation CSV row: {}", ex.getMessage());
            }
        }
        log.info(
                "Inflation CSV import: {} created, {} updated, {} unchanged, {} failed",
                created,
                updated,
                unchanged,
                failed);
        return new CsvImportResult(csv.rows().size(), created + updated, failed, unchanged);
    }

    /** Every stored figure, by currency then year, in exactly the columns the import reads. */
    public String exportCsv() {
        CsvWriter csv = new CsvWriter(CSV_COLUMNS);
        for (InflationRate rate : repository.findAll(Sort.by("unit", "year"))) {
            csv.row(rate.getUnit(), rate.getYear(), rate.getRate());
        }
        return csv.toCsv();
    }

    /** Builds the API's own request from a row, then holds it to the API's own constraints. */
    private InflationRateRequest toRequest(CsvRow row) {
        InflationRateRequest request = new InflationRateRequest(
                row.requireText("unit"), row.requireInteger("year"), row.requirePercent("rate"));
        Set<ConstraintViolation<InflationRateRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new CsvRowException(
                    row.lineNumber(),
                    violations.stream()
                            .map(violation -> "'%s' %s".formatted(violation.getPropertyPath(), violation.getMessage()))
                            .sorted()
                            .collect(Collectors.joining("; ")));
        }
        return request;
    }

    /**
     * Rounded to the column's four places here rather than by the database, so the value the
     * import compares against is the value that will be stored -- otherwise {@code 4.60001} would
     * count as a change on every re-upload while the row never actually moved.
     */
    private static BigDecimal scaled(BigDecimal rate) {
        return rate.setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    private static String key(String unit, Integer year) {
        return unit + "/" + year;
    }

    private static ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("No inflation rate found with id %d".formatted(id));
    }
}
