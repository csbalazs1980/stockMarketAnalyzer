package com.stockmarketanalyzer.backend.dividend.web;

import com.stockmarketanalyzer.backend.common.PageResponse;
import com.stockmarketanalyzer.backend.common.csv.CsvDownload;
import com.stockmarketanalyzer.backend.common.csv.CsvImportResult;
import com.stockmarketanalyzer.backend.dividend.domain.Dividend;
import com.stockmarketanalyzer.backend.dividend.service.DividendService;
import com.stockmarketanalyzer.backend.dividend.service.DividendTotal;
import com.stockmarketanalyzer.backend.dividend.web.dto.DividendRequest;
import com.stockmarketanalyzer.backend.dividend.web.dto.DividendResponse;
import com.stockmarketanalyzer.backend.dividend.web.dto.DividendSummaryResponse;
import com.stockmarketanalyzer.backend.wealth.service.WealthEntryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/dividends")
@Tag(
        name = "Dividends",
        description =
                "Dividends the caller was actually paid: how much, in what currency, on what date, "
                        + "by which instrument. Every operation is scoped to the caller's own data.")
public class DividendController {

    private static final String PROBLEM_JSON = "application/problem+json";

    private final DividendService service;

    @GetMapping
    @Operation(
            summary = "List received dividends",
            description =
                    "Paged, newest payment first. Every filter is optional; each row is valued in "
                            + "'valueIn' using the rate in force on its own payment date. Besides the "
                            + "stored properties, sort=value orders by that converted value (rows with "
                            + "no rate last) -- computed in memory over every matching row, so it pages "
                            + "correctly.")
    @ApiResponse(responseCode = "200", description = "Dividends returned (possibly empty)")
    public PageResponse<DividendResponse> list(
            @Parameter(description = "Inclusive earliest payment date.", example = "2026-01-01")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @Parameter(description = "Inclusive latest payment date.", example = "2026-12-31")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @Parameter(description = "Only dividends paid by this instrument.", example = "1")
                    @RequestParam(required = false)
                    Long instrumentId,
            @Parameter(description = "Only dividends paid in this currency.", example = "USD")
                    @RequestParam(required = false)
                    String unit,
            @Parameter(description = "Unit to value the dividends in. Defaults to HUF.", example = "HUF")
                    @RequestParam(required = false)
                    String valueIn,
            @Parameter(
                            description =
                                    "Also return each row's figures adjusted for cumulative inflation from the payment "
                                            + "year through the latest year with data (amount with its own currency, "
                                            + "value with valueIn's). With this on, sort=amount and sort=value order "
                                            + "by the adjusted figures.")
                    @RequestParam(defaultValue = "false")
                    boolean inflationAdjusted,
            @PageableDefault(size = 100, sort = "paymentDate", direction = Sort.Direction.DESC) Pageable pageable) {
        String target = WealthEntryService.resolveValueUnit(valueIn);
        DividendService.ValuedPage valued =
                service.searchValued(from, to, instrumentId, unit, target, inflationAdjusted, pageable);
        Map<Long, BigDecimal> values = valued.values();
        Map<Long, DividendService.Adjusted> adjusted = valued.adjusted();
        return PageResponse.of(valued.page(), dividend -> {
            DividendService.Adjusted row = adjusted.get(dividend.getId());
            return DividendResponse.from(
                    dividend,
                    values.get(dividend.getId()),
                    target,
                    row == null
                            ? null
                            : new DividendResponse.InflationAdjusted(row.amount(), row.value(), row.complete()));
        });
    }

    @GetMapping("/summary")
    @Operation(
            summary = "What the filter adds up to",
            description =
                    "Takes the same filters as the list and sums every matching row -- not just the "
                            + "page being shown -- so the total does not change as you page through.")
    @ApiResponse(responseCode = "200", description = "Total returned (zero rows is a count of 0, not an error)")
    public DividendSummaryResponse summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long instrumentId,
            @RequestParam(required = false) String unit,
            @Parameter(description = "Unit to total the dividends in. Defaults to HUF.", example = "HUF")
                    @RequestParam(required = false)
                    String valueIn,
            @Parameter(description = "Sum the inflation-adjusted values instead.")
                    @RequestParam(defaultValue = "false")
                    boolean inflationAdjusted) {
        String target = WealthEntryService.resolveValueUnit(valueIn);
        DividendTotal total = service.total(from, to, instrumentId, unit, target, inflationAdjusted);
        return DividendSummaryResponse.from(total, target, inflationAdjusted);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one dividend")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Dividend found"),
        @ApiResponse(
                responseCode = "404",
                description = "No such dividend, or it belongs to someone else",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public DividendResponse get(
            @PathVariable Long id,
            @Parameter(description = "Unit to value the dividend in. Defaults to HUF.", example = "HUF")
                    @RequestParam(required = false)
                    String valueIn) {
        return valued(service.get(id), valueIn);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Record a received dividend",
            description = "The row is owned by the caller; ownership cannot be set from the payload.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Dividend stored"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "instrumentId does not resolve to a known instrument",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public DividendResponse create(@RequestBody @Valid DividendRequest request) {
        return valued(service.create(request), null);
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Import received dividends from a CSV file",
            description =
                    "Records a whole file of payments at once. The company is named by its ticker "
                            + "symbol, so an unknown symbol is a row that fails rather than a new "
                            + "instrument. Comma- and semicolon-delimited files are both accepted (the "
                            + "delimiter also decides whether the comma or the dot is the decimal "
                            + "point), the columns may be in any order, and a blank paymentDate means "
                            + "today. A row that cannot be read is skipped and counted rather than "
                            + "failing the upload, and a row matching one already stored is counted as "
                            + "a duplicate and skipped, so re-uploading an overlapping file is safe.")
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

    @GetMapping(path = "/import/sample", produces = "text/csv")
    @Operation(
            summary = "Download a sample CSV",
            description = "The header row the import expects, plus one example row to overwrite.")
    @ApiResponse(responseCode = "200", description = "The sample file")
    public ResponseEntity<byte[]> importSample() {
        return CsvDownload.sample("dividends-sample.csv", DividendService.CSV_SAMPLE);
    }

    @GetMapping(path = "/export", produces = "text/csv")
    @Operation(
            summary = "Download the caller's dividends as CSV",
            description =
                    "Every payment the caller has recorded, in the same columns the import reads, so the "
                            + "file can be kept as a backup, edited in a spreadsheet and uploaded again, or carried "
                            + "to another account. The company is named by its ticker, never by database id. The "
                            + "list filters are not applied: this is the whole of the caller's own data, and nobody "
                            + "else's.")
    @ApiResponse(
            responseCode = "200",
            description = "The file -- a header row on its own when there is nothing to export")
    public ResponseEntity<byte[]> exportCsv() {
        return CsvDownload.export("dividends-%s.csv".formatted(LocalDate.now()), service.exportCsv());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace a dividend")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Dividend updated"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "No such dividend, or it belongs to someone else",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public DividendResponse update(@PathVariable Long id, @RequestBody @Valid DividendRequest request) {
        return valued(service.update(id, request), null);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a dividend")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Dividend deleted"),
        @ApiResponse(
                responseCode = "404",
                description = "No such dividend, or it belongs to someone else",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    private DividendResponse valued(Dividend dividend, String valueIn) {
        String target = WealthEntryService.resolveValueUnit(valueIn);
        BigDecimal value = service.valuesIn(List.of(dividend), target).get(dividend.getId());
        return DividendResponse.from(dividend, value, target);
    }
}
