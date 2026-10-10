package com.stockmarketanalyzer.backend.wealth.web;

import com.stockmarketanalyzer.backend.common.PageResponse;
import com.stockmarketanalyzer.backend.common.ResourceNotFoundException;
import com.stockmarketanalyzer.backend.common.csv.CsvDownload;
import com.stockmarketanalyzer.backend.common.csv.CsvImportResult;
import com.stockmarketanalyzer.backend.wealth.service.ExchangeRateService;
import com.stockmarketanalyzer.backend.wealth.service.RateFeedService;
import com.stockmarketanalyzer.backend.wealth.web.dto.ExchangeRateRequest;
import com.stockmarketanalyzer.backend.wealth.web.dto.ExchangeRateResponse;
import com.stockmarketanalyzer.backend.wealth.web.dto.RateRefreshResponse;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/wealth/exchange-rates")
@Tag(
        name = "Exchange rates",
        description =
                "Conversion factors between units (USD to HUF, OZ to HUF, ...): the caller's personal "
                        + "rates plus the global ones every user shares. Wealth entries and dividends "
                        + "are valued from these, and a personal rate always wins over a global one.")
public class ExchangeRateController {

    private static final String PROBLEM_JSON = "application/problem+json";

    private final ExchangeRateService service;
    private final RateFeedService rateFeed;

    @GetMapping
    @Operation(
            summary = "List exchange rates",
            description =
                    "The caller's personal rates and the global ones. Paged, newest first. Every filter is "
                            + "optional.")
    @ApiResponse(responseCode = "200", description = "Rates returned (possibly empty)")
    public PageResponse<ExchangeRateResponse> list(
            @Parameter(description = "true for global rates only, false for the caller's own only; omit for both.")
                    @RequestParam(required = false)
                    Boolean global,
            @Parameter(description = "Unit converted from.", example = "USD") @RequestParam(required = false)
                    String sourceUnit,
            @Parameter(description = "Unit converted to.", example = "HUF") @RequestParam(required = false)
                    String destinationUnit,
            @Parameter(description = "Inclusive earliest quote date.", example = "2026-01-01")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @Parameter(description = "Inclusive latest quote date.", example = "2026-08-31")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @PageableDefault(size = 100, sort = "rateDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.of(
                service.search(global, sourceUnit, destinationUnit, from, to, pageable), ExchangeRateResponse::from);
    }

    @GetMapping("/latest")
    @Operation(
            summary = "The newest quote for every pair",
            description =
                    "One row per pair: the most recent rate stored, whatever its date. What "
                            + "'/in-force' answers for one pair on one date, this answers for every "
                            + "pair with no date at all -- which is what a figure carrying no date of "
                            + "its own, such as a recurring expense, has to be converted with.")
    @ApiResponse(responseCode = "200", description = "Quotes returned (possibly empty)")
    public List<ExchangeRateResponse> latest() {
        return service.latestPerPair().stream().map(ExchangeRateResponse::from).toList();
    }

    @GetMapping("/in-force")
    @Operation(
            summary = "Rate in force on a date",
            description =
                    "The newest quote for the pair on or before the given date -- the caller's own if they "
                            + "have one, otherwise the global one. 404 when there is neither.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Quote found"),
        @ApiResponse(
                responseCode = "404",
                description = "The caller has never quoted that pair on or before that date",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ExchangeRateResponse inForce(
            @Parameter(description = "Unit converted from.", example = "USD") @RequestParam String sourceUnit,
            @Parameter(description = "Unit converted to.", example = "HUF") @RequestParam String destinationUnit,
            @Parameter(description = "Date to look up. Defaults to today.", example = "2026-08-05")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate on) {
        LocalDate asOf = on != null ? on : LocalDate.now();
        return service.findInForce(sourceUnit, destinationUnit, asOf)
                .map(ExchangeRateResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No %s to %s rate quoted on or before %s".formatted(sourceUnit, destinationUnit, asOf)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Store an exchange rate",
            description =
                    "Idempotent per kind, pair and date: re-posting the same day overwrites the quote. "
                            + "global=true stores a shared rate and requires the ADMIN role.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Rate stored"),
        @ApiResponse(
                responseCode = "403",
                description = "A global rate was asked for without the ADMIN role",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed, or source and destination are the same unit",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ExchangeRateResponse upsert(@RequestBody @Valid ExchangeRateRequest request) {
        return ExchangeRateResponse.from(service.upsert(request));
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "Fetch today's published rates and store them",
            description =
                    "Reads the configured pairs from the upstream feed (Frankfurter, pinned to the "
                            + "Hungarian central bank by default) and stores each one as a global rate "
                            + "(ADMIN only). Each quote is stored under the feed's own publication date, "
                            + "not today's -- asked on a Sunday the feed answers with Friday's rate. "
                            + "Because the store is an upsert on (pair, date), pressing this twice is "
                            + "harmless.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Rates fetched and stored"),
        @ApiResponse(
                responseCode = "403",
                description = "The caller is not an administrator",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "502",
                description = "The upstream feed was unreachable, too slow, or answered with nonsense",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public RateRefreshResponse refresh() {
        return rateFeed.refresh();
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Import exchange rates from a CSV file",
            description =
                    "Stores a whole file of quotes at once. Comma- and semicolon-delimited files "
                            + "are both accepted (the delimiter also decides whether the comma or the "
                            + "dot is the decimal point), the columns may be in any order, and a blank "
                            + "rateDate means today. A row that cannot be read is skipped and counted "
                            + "rather than failing the upload; the response says how many of how many "
                            + "were stored. Re-uploading a file overwrites the quotes it stored before, "
                            + "because each row is an upsert on (pair, date). global=true stores the "
                            + "whole file as shared rates and requires the ADMIN role.")
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
                    MultipartFile file,
            @Parameter(description = "Store every row as a global rate. ADMIN only.")
                    @RequestParam(required = false, defaultValue = "false")
                    boolean global) {
        return service.importCsv(file, global);
    }

    @GetMapping(path = "/import/sample", produces = "text/csv")
    @Operation(
            summary = "Download a sample CSV",
            description = "The header row the import expects, plus one example row to overwrite.")
    @ApiResponse(responseCode = "200", description = "The sample file")
    public ResponseEntity<byte[]> importSample() {
        return CsvDownload.sample("exchange-rates-sample.csv", ExchangeRateService.CSV_SAMPLE);
    }

    @GetMapping(path = "/export", produces = "text/csv")
    @Operation(
            summary = "Download the caller's exchange rates as CSV",
            description =
                    "Every quote the caller has stored, in the same columns the import reads, so the file "
                            + "can be kept as a backup, edited in a spreadsheet and uploaded again, or carried to "
                            + "another account. The list filters are not applied: this is the whole of the caller's "
                            + "own data, and nobody else's -- global rates are not included.")
    @ApiResponse(
            responseCode = "200",
            description = "The file -- a header row on its own when there is nothing to export")
    public ResponseEntity<byte[]> exportCsv() {
        return CsvDownload.export("exchange-rates-%s.csv".formatted(LocalDate.now()), service.exportCsv());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an exchange rate")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Rate deleted"),
        @ApiResponse(
                responseCode = "404",
                description = "No such rate, or it belongs to someone else",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "403",
                description = "A global rate, and the caller is not an administrator",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
