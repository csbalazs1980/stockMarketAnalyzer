package com.stockmarketanalyzer.backend.instrument.service;

import com.stockmarketanalyzer.backend.common.ResourceConflictException;
import com.stockmarketanalyzer.backend.common.ResourceNotFoundException;
import com.stockmarketanalyzer.backend.common.csv.CsvFile;
import com.stockmarketanalyzer.backend.common.csv.CsvImportResult;
import com.stockmarketanalyzer.backend.common.csv.CsvRow;
import com.stockmarketanalyzer.backend.common.csv.CsvRowException;
import com.stockmarketanalyzer.backend.common.csv.CsvWriter;
import com.stockmarketanalyzer.backend.dividend.repository.DividendRepository;
import com.stockmarketanalyzer.backend.instrument.domain.AssetType;
import com.stockmarketanalyzer.backend.instrument.domain.Instrument;
import com.stockmarketanalyzer.backend.instrument.repository.InstrumentOptionView;
import com.stockmarketanalyzer.backend.instrument.repository.InstrumentRepository;
import com.stockmarketanalyzer.backend.instrument.repository.InstrumentSpecifications;
import com.stockmarketanalyzer.backend.instrument.web.dto.InstrumentRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
public class InstrumentService {

    /** Every column an instrument CSV can carry, in the order the sample and the export write them. */
    public static final List<String> CSV_COLUMNS =
            List.of("symbol", "name", "exchange", "currency", "assetType", "sector", "active");

    /**
     * The columns a file must have. {@code exchange} and {@code sector} are optional in the API
     * too, and a file without {@code active} simply leaves every instrument's flag as it is (new
     * ones start active), so a hand-written three-column list of tickers still imports.
     */
    public static final List<String> CSV_REQUIRED_COLUMNS = List.of("symbol", "name", "currency", "assetType");

    /** The downloadable sample: the header, and one row showing what each column looks like. */
    public static final String CSV_SAMPLE =
            """
            symbol,name,exchange,currency,assetType,sector,active
            AAPL,Apple Inc.,NASDAQ,USD,STOCK,Technology,true
            """;

    private final InstrumentRepository repository;
    private final DividendRepository dividends;
    private final Validator validator;

    public Page<Instrument> search(String search, AssetType assetType, String exchange, Boolean active,
            Pageable pageable) {
        return repository.findAll(InstrumentSpecifications.filter(search, assetType, exchange, active), pageable);
    }

    /**
     * The whole instrument universe as picker options, sorted by ticker. Unpaged: a dropdown that
     * silently stops at page one is worse than no dropdown, and the projection keeps the payload
     * small enough that one response beats paging it.
     *
     * @param active {@code null} for every instrument, otherwise only the matching ones
     */
    public List<InstrumentOptionView> options(Boolean active) {
        return active == null
                ? repository.findAllByOrderBySymbolAsc()
                : repository.findAllByActiveOrderBySymbolAsc(active);
    }

    public Instrument getBySymbol(String symbol) {
        String normalised = Instrument.normaliseSymbol(symbol);
        return repository.findBySymbol(normalised).orElseThrow(() -> ResourceNotFoundException.instrument(normalised));
    }

    @Transactional
    public Instrument create(InstrumentRequest request) {
        String symbol = Instrument.normaliseSymbol(request.symbol());
        if (repository.existsBySymbol(symbol)) {
            throw new ResourceConflictException("An instrument with symbol '%s' already exists".formatted(symbol));
        }
        Instrument saved = repository.save(newInstrument(request));
        log.info("Created instrument {} ({})", saved.getSymbol(), saved.getAssetType());
        return saved;
    }

    @Transactional
    public Instrument update(String symbol, InstrumentRequest request) {
        Instrument instrument = getBySymbol(symbol);
        String newSymbol = Instrument.normaliseSymbol(request.symbol());
        if (!instrument.getSymbol().equals(newSymbol) && repository.existsBySymbol(newSymbol)) {
            throw new ResourceConflictException("An instrument with symbol '%s' already exists".formatted(newSymbol));
        }
        apply(instrument, request);
        return repository.save(instrument);
    }

    /**
     * Price bars cascade -- market data can be fetched again. Dividends do not: they were typed in
     * by somebody, and {@code dividend.instrument_id} is {@code ON DELETE RESTRICT}. The check is
     * made here rather than left to the constraint so the administrator is told <em>why</em> and
     * what to do instead, not handed the generic "the database rejected this change".
     */
    @Transactional
    public void delete(String symbol) {
        Instrument instrument = getBySymbol(symbol);
        if (dividends.existsByInstrumentId(instrument.getId())) {
            throw new ResourceConflictException(
                    ("'%s' cannot be deleted: dividends have been recorded against it. Deactivate it "
                                    + "instead -- it leaves the pickers and the recorded history stays.")
                            .formatted(instrument.getSymbol()));
        }
        repository.delete(instrument);
        log.info("Deleted instrument {} and its price history", instrument.getSymbol());
    }

    /**
     * Stores a whole CSV file of instruments, matched on ticker: a symbol already in the universe is
     * updated from its row, a new one is created.
     *
     * <p>Every row goes through the same bean-validation rules as {@code POST /instruments}, so the
     * file and the form cannot drift on what a valid instrument is. A row that fails is counted and
     * skipped rather than failing the file. A row identical to what is already stored is counted as
     * a duplicate -- re-uploading an export changes nothing and says so.
     *
     * <p>The universe is loaded once into a map rather than queried per row: a few thousand
     * instruments is one cheap query, against up to ten thousand lookups for a full file.
     */
    @Transactional
    public CsvImportResult importCsv(MultipartFile file) {
        CsvFile csv = CsvFile.read(file, CSV_REQUIRED_COLUMNS);
        Map<String, Instrument> bySymbol = new HashMap<>();
        for (Instrument instrument : repository.findAll()) {
            bySymbol.put(instrument.getSymbol(), instrument);
        }

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int failed = 0;
        for (CsvRow row : csv.rows()) {
            try {
                InstrumentRequest request = toRequest(row);
                String symbol = Instrument.normaliseSymbol(request.symbol());
                Instrument existing = bySymbol.get(symbol);
                if (existing == null) {
                    Instrument saved = repository.save(newInstrument(request));
                    bySymbol.put(saved.getSymbol(), saved);
                    created++;
                } else if (apply(existing, request)) {
                    // Managed entity: the change is flushed at commit, no save() needed.
                    updated++;
                } else {
                    unchanged++;
                }
            } catch (CsvRowException | IllegalArgumentException ex) {
                failed++;
                log.debug("Skipped instrument CSV row: {}", ex.getMessage());
            }
        }
        log.info(
                "Instrument CSV import: {} created, {} updated, {} unchanged, {} failed",
                created,
                updated,
                unchanged,
                failed);
        return new CsvImportResult(csv.rows().size(), created + updated, failed, unchanged);
    }

    /** The whole universe, sorted by ticker, in exactly the columns the import reads. */
    public String exportCsv() {
        CsvWriter csv = new CsvWriter(CSV_COLUMNS);
        for (Instrument instrument : repository.findAll(Sort.by("symbol"))) {
            csv.row(
                    instrument.getSymbol(),
                    instrument.getName(),
                    instrument.getExchange(),
                    instrument.getCurrency(),
                    instrument.getAssetType(),
                    instrument.getSector(),
                    instrument.isActive());
        }
        return csv.toCsv();
    }

    private static Instrument newInstrument(InstrumentRequest request) {
        Instrument instrument = new Instrument(
                request.symbol(),
                request.name(),
                blankToNull(request.exchange()),
                request.currency().toUpperCase(Locale.ROOT),
                request.assetType());
        instrument.setSector(blankToNull(request.sector()));
        instrument.setActive(request.active() == null || request.active());
        return instrument;
    }

    /**
     * Copies a request onto an instrument, shared by {@code PUT} and the CSV import.
     *
     * @return whether anything actually changed -- the import reports untouched rows separately,
     *     and leaving an unchanged entity alone also leaves its {@code updated_at} alone
     */
    private static boolean apply(Instrument instrument, InstrumentRequest request) {
        String symbol = Instrument.normaliseSymbol(request.symbol());
        String exchange = blankToNull(request.exchange());
        String currency = request.currency().toUpperCase(Locale.ROOT);
        String sector = blankToNull(request.sector());
        boolean active = request.active() == null ? instrument.isActive() : request.active();

        boolean changed = !Objects.equals(instrument.getSymbol(), symbol)
                || !Objects.equals(instrument.getName(), request.name())
                || !Objects.equals(instrument.getExchange(), exchange)
                || !Objects.equals(instrument.getCurrency(), currency)
                || instrument.getAssetType() != request.assetType()
                || !Objects.equals(instrument.getSector(), sector)
                || instrument.isActive() != active;
        if (changed) {
            instrument.setSymbol(symbol);
            instrument.setName(request.name());
            instrument.setExchange(exchange);
            instrument.setCurrency(currency);
            instrument.setAssetType(request.assetType());
            instrument.setSector(sector);
            instrument.setActive(active);
        }
        return changed;
    }

    /** Builds the API's own request from a row, then holds it to the API's own constraints. */
    private InstrumentRequest toRequest(CsvRow row) {
        InstrumentRequest request = new InstrumentRequest(
                row.requireText("symbol"),
                row.requireText("name"),
                row.text("exchange"),
                row.requireText("currency"),
                assetType(row),
                row.text("sector"),
                active(row));
        Set<ConstraintViolation<InstrumentRequest>> violations = validator.validate(request);
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

    /** Case-insensitive, so {@code stock} and {@code Etf} from a hand-typed sheet still match. */
    private static AssetType assetType(CsvRow row) {
        String raw = row.requireText("assetType");
        try {
            return AssetType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new CsvRowException(
                    row.lineNumber(),
                    "'assetType' must be one of %s, not '%s'".formatted(Arrays.toString(AssetType.values()), raw));
        }
    }

    /**
     * {@code true}/{@code false} as the export writes them, plus the spellings a spreadsheet or a
     * person tends to produce instead -- including the Hungarian ones. Blank means "not given":
     * a new instrument starts active and an existing one keeps its flag.
     */
    private static Boolean active(CsvRow row) {
        String raw = row.text("active");
        if (raw == null) {
            return null;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "y", "1", "igen", "i", "active", "aktív", "aktiv" -> Boolean.TRUE;
            case "false", "no", "n", "0", "nem", "inactive", "inaktív", "inaktiv" -> Boolean.FALSE;
            default -> throw new CsvRowException(
                    row.lineNumber(), "'active' must be true or false, not '%s'".formatted(raw));
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
