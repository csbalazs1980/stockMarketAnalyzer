package com.stockmarketanalyzer.backend.inflation.web;

import com.stockmarketanalyzer.backend.common.PageResponse;
import com.stockmarketanalyzer.backend.common.csv.CsvDownload;
import com.stockmarketanalyzer.backend.common.csv.CsvImportResult;
import com.stockmarketanalyzer.backend.inflation.service.InflationFeedService;
import com.stockmarketanalyzer.backend.inflation.service.InflationRateService;
import com.stockmarketanalyzer.backend.inflation.web.dto.InflationFetchResponse;
import com.stockmarketanalyzer.backend.inflation.web.dto.InflationRateRequest;
import com.stockmarketanalyzer.backend.inflation.web.dto.InflationRateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Reference data, not personal data -- hence {@code /api/v1/inflation-rates} rather than a path
 * under {@code /api/v1/wealth}: it picks up the standard rule that reads need any valid token
 * and writes need the ADMIN role. That covers the CSV import too (a POST); the sample and the
 * export are GETs and readable by any signed-in user, which is fine for published statistics.
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/inflation-rates")
@Tag(
        name = "Inflation",
        description = "Annual inflation per currency, in percentage points. Shared across all users.")
public class InflationRateController {

    private static final String PROBLEM_JSON = "application/problem+json";

    private final InflationRateService service;
    private final InflationFeedService feed;

    @GetMapping
    @Operation(summary = "List inflation rates", description = "Paged, newest year first. Filters are optional.")
    @ApiResponse(responseCode = "200", description = "Rates returned (possibly empty)")
    public PageResponse<InflationRateResponse> list(
            @Parameter(description = "Currency to filter on.", example = "HUF") @RequestParam(required = false)
                    String unit,
            @Parameter(description = "Earliest year, inclusive.", example = "2015")
                    @RequestParam(required = false)
                    Integer fromYear,
            @Parameter(description = "Latest year, inclusive.", example = "2026") @RequestParam(required = false)
                    Integer toYear,
            @PageableDefault(size = 100, sort = "year", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.of(service.search(unit, fromYear, toYear, pageable), InflationRateResponse::from);
    }

    @GetMapping("/units")
    @Operation(
            summary = "Currencies with inflation data",
            description = "Every currency that has at least one stored figure, alphabetically.")
    @ApiResponse(responseCode = "200", description = "Currencies returned (possibly empty)")
    public List<String> units() {
        return service.units();
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Import inflation rates from a CSV file",
            description =
                    "Stores a whole file of figures at once, matched on currency and year: a pair already "
                            + "stored is updated from its row, a new one is created. Columns unit, year and "
                            + "rate are all required; rate is in percentage points and may carry a trailing %. "
                            + "Comma- and semicolon-delimited files are both accepted. A row that cannot be read "
                            + "is skipped and counted; a row identical to what is stored is counted as a "
                            + "duplicate. ADMIN only.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The file was read; the counts say what happened"),
        @ApiResponse(
                responseCode = "400",
                description = "Not a CSV file, empty, or missing a required column -- nothing was stored",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CsvImportResult importCsv(
            @Parameter(description = "A .csv file with the columns the sample download shows.")
                    @RequestParam("file")
                    MultipartFile file) {
        return service.importCsv(file);
    }

    // Two literal segments, so this beats /{unit}/{year} -- a currency literally called IMPORT
    // would have no sample year to clash with anyway.
    @GetMapping(path = "/import/sample", produces = "text/csv")
    @Operation(
            summary = "Download a sample inflation CSV",
            description = "The header row the import expects, plus example rows to overwrite.")
    @ApiResponse(responseCode = "200", description = "The sample file")
    public ResponseEntity<byte[]> importSample() {
        return CsvDownload.sample("inflation-rates-sample.csv", InflationRateService.CSV_SAMPLE);
    }

    @GetMapping(path = "/export", produces = "text/csv")
    @Operation(
            summary = "Download every inflation rate as CSV",
            description =
                    "Every stored figure, by currency then year, in exactly the columns the import reads -- "
                            + "so it can be edited in a spreadsheet and uploaded again. Filters are not applied.")
    @ApiResponse(responseCode = "200", description = "The file")
    public ResponseEntity<byte[]> exportCsv() {
        return CsvDownload.export("inflation-rates-%s.csv".formatted(LocalDate.now()), service.exportCsv());
    }

    @PostMapping("/fetch")
    @Operation(
            summary = "Fetch new years from Eurostat and store them",
            description =
                    "For each configured currency (HUF, EUR, USD by default) reads the annual average HICP "
                            + "rate from Eurostat and stores only the years after the latest one already held -- "
                            + "every published year when the currency has none. Stored years are never "
                            + "overwritten, even if the source has revised them. ADMIN only.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Fetched; the response says what was added per currency"),
        @ApiResponse(
                responseCode = "403",
                description = "The caller is not an administrator",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "502",
                description = "Eurostat was unreachable, too slow, or answered with something unexpected -- nothing was stored",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public InflationFetchResponse fetch() {
        return feed.fetch();
    }

    @GetMapping("/{unit}/{year}")
    @Operation(summary = "Rate for one currency and year")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Rate found"),
        @ApiResponse(
                responseCode = "404",
                description = "Nothing recorded for that currency and year",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public InflationRateResponse get(
            @Parameter(description = "Currency, case-insensitive.", example = "HUF") @PathVariable String unit,
            @Parameter(description = "Calendar year.", example = "2025") @PathVariable Integer year) {
        return InflationRateResponse.from(service.getByUnitAndYear(unit, year));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Store an inflation rate",
            description =
                    "Idempotent per currency and year: re-posting a year replaces the figure, so a "
                            + "provisional estimate can be superseded by the final print.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Rate stored"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public InflationRateResponse upsert(@RequestBody @Valid InflationRateRequest request) {
        return InflationRateResponse.from(service.upsert(request));
    }

    @PutMapping("/{id}")
    @Operation(
            summary = "Replace an inflation rate",
            description =
                    "Full update of one row by id. Unlike POST the currency and year may change too, so a "
                            + "figure typed under the wrong year can be corrected in place.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Updated"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "No rate with that id",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Another row already holds that currency and year",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public InflationRateResponse update(@PathVariable Long id, @RequestBody @Valid InflationRateRequest request) {
        return InflationRateResponse.from(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an inflation rate")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Rate deleted"),
        @ApiResponse(
                responseCode = "404",
                description = "No rate with that id",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
