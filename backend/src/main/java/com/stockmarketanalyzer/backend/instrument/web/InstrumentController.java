package com.stockmarketanalyzer.backend.instrument.web;

import com.stockmarketanalyzer.backend.common.PageResponse;
import com.stockmarketanalyzer.backend.common.csv.CsvDownload;
import com.stockmarketanalyzer.backend.common.csv.CsvImportResult;
import com.stockmarketanalyzer.backend.instrument.domain.AssetType;
import com.stockmarketanalyzer.backend.instrument.domain.Instrument;
import com.stockmarketanalyzer.backend.instrument.service.InstrumentService;
import com.stockmarketanalyzer.backend.instrument.web.dto.InstrumentOption;
import com.stockmarketanalyzer.backend.instrument.web.dto.InstrumentRequest;
import com.stockmarketanalyzer.backend.instrument.web.dto.InstrumentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/instruments")
@Tag(name = "Instruments", description = "Reference data for tradable instruments.")
public class InstrumentController {

    private static final String PROBLEM_JSON = "application/problem+json";

    private final InstrumentService service;

    @GetMapping
    @Operation(
            summary = "List instruments",
            description = "Paged listing. Every filter is optional and they combine with AND.")
    public PageResponse<InstrumentResponse> list(
            @Parameter(description = "Case-insensitive substring match on symbol or name.", example = "apple")
                    @RequestParam(required = false)
                    String search,
            @Parameter(description = "Exact asset type match.") @RequestParam(required = false) AssetType assetType,
            @Parameter(description = "Case-insensitive exact exchange match.", example = "NASDAQ")
                    @RequestParam(required = false)
                    String exchange,
            @Parameter(description = "Restrict to active or inactive instruments.") @RequestParam(required = false)
                    Boolean active,
            @PageableDefault(size = 25, sort = "symbol", direction = Sort.Direction.ASC) Pageable pageable) {
        return PageResponse.of(service.search(search, assetType, exchange, active, pageable), InstrumentResponse::from);
    }

    @GetMapping("/options")
    @Operation(
            summary = "List instruments for a picker",
            description =
                    "Every matching instrument in one unpaged response, sorted by symbol, reduced to "
                            + "id/symbol/name/currency. Meant for dropdowns: paging a picker hides the "
                            + "tail of the alphabet, and the full listing above is the wrong shape for "
                            + "a few thousand rows. Cache it on the client -- this data changes rarely.")
    public List<InstrumentOption> options(
            @Parameter(description = "Restrict to active or inactive instruments; omit for all.")
                    @RequestParam(required = false)
                    Boolean active) {
        return service.options(active).stream().map(InstrumentOption::from).toList();
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Import instruments from a CSV file",
            description =
                    "Creates or updates a whole file of instruments at once, matched on ticker symbol: a "
                            + "symbol already stored is updated from its row, a new one is created. Rows are "
                            + "held to the same rules as the single-instrument API. symbol, name, currency and "
                            + "assetType are required columns; exchange, sector and active are optional, and a "
                            + "blank active keeps an existing instrument's flag. Comma- and semicolon-delimited "
                            + "files are both accepted. A row that cannot be read is skipped and counted; a row "
                            + "identical to what is stored is counted as a duplicate. ADMIN only.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The file was read; the counts say what happened"),
        @ApiResponse(
                responseCode = "400",
                description = "Not a CSV file, empty, or missing a required column -- nothing was stored",
                content =
                        @Content(
                                mediaType = PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CsvImportResult importCsv(
            @Parameter(description = "A .csv file with the columns the sample download shows.")
                    @RequestParam("file")
                    MultipartFile file) {
        return service.importCsv(file);
    }

    @GetMapping(path = "/import/sample", produces = "text/csv")
    @Operation(
            summary = "Download a sample instrument CSV",
            description = "The header row the import expects, plus one example row to overwrite.")
    @ApiResponse(responseCode = "200", description = "The sample file")
    public ResponseEntity<byte[]> importSample() {
        return CsvDownload.sample("instruments-sample.csv", InstrumentService.CSV_SAMPLE);
    }

    // A literal path, so like /options it beats /{symbol} -- and would shadow an instrument whose
    // ticker is literally EXPORT.
    @GetMapping(path = "/export", produces = "text/csv")
    @Operation(
            summary = "Download every instrument as CSV",
            description =
                    "The whole universe, sorted by symbol, in exactly the columns the import reads -- so it "
                            + "can be edited in a spreadsheet and uploaded again. Filters are not applied.")
    @ApiResponse(responseCode = "200", description = "The file")
    public ResponseEntity<byte[]> exportCsv() {
        return CsvDownload.export("instruments-%s.csv".formatted(LocalDate.now()), service.exportCsv());
    }

    // Mapped above /{symbol} for readability only -- a literal path always beats a template, so
    // "options" is never taken for a ticker. (It would shadow an instrument actually called
    // OPTIONS, which is why no such symbol may be created.)
    @GetMapping("/{symbol}")
    @Operation(summary = "Fetch one instrument", description = "Symbol lookup is case-insensitive.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Instrument found"),
        @ApiResponse(
                responseCode = "404",
                description = "No instrument with that symbol",
                content =
                        @Content(
                                mediaType = PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public InstrumentResponse get(
            @Parameter(description = "Ticker symbol.", example = "AAPL") @PathVariable String symbol) {
        return InstrumentResponse.from(service.getBySymbol(symbol));
    }

    @PostMapping
    @Operation(summary = "Create an instrument", description = "The symbol is stored upper-cased.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Created; Location points at the new resource"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed",
                content =
                        @Content(
                                mediaType = PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Symbol already exists",
                content =
                        @Content(
                                mediaType = PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<InstrumentResponse> create(@Valid @RequestBody InstrumentRequest request) {
        Instrument created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/instruments/" + created.getSymbol()))
                .body(InstrumentResponse.from(created));
    }

    @PutMapping("/{symbol}")
    @Operation(summary = "Replace an instrument", description = "Full update; renaming the symbol is allowed.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Updated"),
        @ApiResponse(
                responseCode = "404",
                description = "No instrument with that symbol",
                content =
                        @Content(
                                mediaType = PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "The new symbol is already taken",
                content =
                        @Content(
                                mediaType = PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public InstrumentResponse update(
            @Parameter(description = "Current ticker symbol.", example = "AAPL") @PathVariable String symbol,
            @Valid @RequestBody InstrumentRequest request) {
        return InstrumentResponse.from(service.update(symbol, request));
    }

    @DeleteMapping("/{symbol}")
    @Operation(
            summary = "Delete an instrument",
            description =
                    "Cascades to every price bar stored for the instrument -- market data can be "
                            + "refetched. It does not cascade to dividends: those were typed in by a "
                            + "user, so an instrument somebody has been paid by is refused instead.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Deleted"),
        @ApiResponse(
                responseCode = "404",
                description = "No instrument with that symbol",
                content =
                        @Content(
                                mediaType = PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "A recorded dividend still references this instrument",
                content =
                        @Content(
                                mediaType = PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<Void> delete(
            @Parameter(description = "Ticker symbol.", example = "AAPL") @PathVariable String symbol) {
        service.delete(symbol);
        return ResponseEntity.noContent().build();
    }
}
