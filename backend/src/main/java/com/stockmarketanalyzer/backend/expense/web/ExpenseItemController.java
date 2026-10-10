package com.stockmarketanalyzer.backend.expense.web;

import com.stockmarketanalyzer.backend.common.PageResponse;
import com.stockmarketanalyzer.backend.common.csv.CsvDownload;
import com.stockmarketanalyzer.backend.common.csv.CsvImportResult;
import com.stockmarketanalyzer.backend.expense.domain.RecurrencePeriod;
import com.stockmarketanalyzer.backend.expense.service.ExpenseItemService;
import com.stockmarketanalyzer.backend.expense.web.dto.ExpenseItemRequest;
import com.stockmarketanalyzer.backend.expense.web.dto.ExpenseItemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
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

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/expenses/items")
@Tag(
        name = "Expenses",
        description =
                "Recurring costs: what they are, what one occurrence costs, and how often it comes "
                        + "back. Every operation is scoped to the caller's own data. What an expense "
                        + "works out to per month or per year is computed by the client, from the "
                        + "interval and period returned here -- see ExpenseItemResponse.")
public class ExpenseItemController {

    private static final String PROBLEM_JSON = "application/problem+json";

    private final ExpenseItemService service;

    @GetMapping
    @Operation(
            summary = "List recurring expenses",
            description =
                    "Paged, by name. Every filter is optional. Rows are returned exactly as entered, "
                            + "with an 'active' flag derived from today and the two optional dates.")
    @ApiResponse(responseCode = "200", description = "Expenses returned (possibly empty)")
    public PageResponse<ExpenseItemResponse> list(
            @Parameter(description = "Case-insensitive substring of the name.", example = "insur")
                    @RequestParam(required = false)
                    String search,
            @Parameter(description = "Only expenses paid in this currency.", example = "HUF")
                    @RequestParam(required = false)
                    String unit,
            @Parameter(description = "Only expenses repeating over this period.", example = "MONTHLY")
                    @RequestParam(required = false)
                    RecurrencePeriod period,
            @Parameter(description = "Only expenses carrying this category.", example = "4")
                    @RequestParam(required = false)
                    Long categoryId,
            @Parameter(
                            description =
                                    "True to hide expenses that have not started yet or have already "
                                            + "expired. Defaults to false, so nothing is hidden unless asked.",
                            example = "true")
                    @RequestParam(required = false, defaultValue = "false")
                    boolean activeOnly,
            @PageableDefault(size = 100, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        LocalDate today = LocalDate.now();
        return PageResponse.of(
                service.search(search, unit, period, categoryId, activeOnly ? today : null, pageable),
                item -> ExpenseItemResponse.from(item, today));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one recurring expense")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Expense found"),
        @ApiResponse(
                responseCode = "404",
                description = "No such expense, or it belongs to someone else",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ExpenseItemResponse get(@PathVariable Long id) {
        return ExpenseItemResponse.from(service.get(id), LocalDate.now());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Record a recurring expense",
            description =
                    "The row is owned by the caller; ownership cannot be set from the payload. "
                            + "categoryIds must name your own categories or global ones.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Expense stored"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed, or expiryDate is before startDate",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "One of the categoryIds does not exist or is not yours",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ExpenseItemResponse create(@RequestBody @Valid ExpenseItemRequest request) {
        return ExpenseItemResponse.from(service.create(request), LocalDate.now());
    }

    @PutMapping("/{id}")
    @Operation(
            summary = "Replace a recurring expense",
            description = "categoryIds is the complete tag set afterwards, not a delta.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Expense updated"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed, or expiryDate is before startDate",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "No such expense, or a categoryId that is not yours",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ExpenseItemResponse update(@PathVariable Long id, @RequestBody @Valid ExpenseItemRequest request) {
        return ExpenseItemResponse.from(service.update(id, request), LocalDate.now());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Delete a recurring expense",
            description = "The categories it carried are left alone -- only the links to them go.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Expense deleted"),
        @ApiResponse(
                responseCode = "404",
                description = "No such expense, or it belongs to someone else",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Import recurring expenses from a CSV file",
            description =
                    "Records a whole file of recurring costs at once. name, amount, unit and period "
                            + "are required; interval defaults to 1, both dates may be blank, and "
                            + "categories is a '|'-separated list in one cell -- a tag named there is "
                            + "created as a personal category if no global or personal one matches. "
                            + "Comma- and semicolon-delimited files are both accepted (the delimiter "
                            + "also decides whether the comma or the dot is the decimal point) and the "
                            + "columns may be in any order. A row that cannot be read is skipped and "
                            + "counted; a row whose name you already have is counted as a duplicate and "
                            + "skipped, so an overlapping file is safe to upload.")
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
            description = "The header row the import expects, plus three example rows to overwrite.")
    @ApiResponse(responseCode = "200", description = "The sample file")
    public ResponseEntity<byte[]> importSample() {
        return CsvDownload.sample("expense-items-sample.csv", ExpenseItemService.CSV_SAMPLE);
    }

    @GetMapping(path = "/export", produces = "text/csv")
    @Operation(
            summary = "Download the caller's expenses as CSV",
            description =
                    "Every recurring cost the caller has recorded, in the same columns the import reads, "
                            + "tags included as the pipe-separated cell. Keep it as a backup, edit it in a "
                            + "spreadsheet and upload it again, or carry it to another account. The list filters are "
                            + "not applied: this is the whole of the caller's own data, and nobody else's.")
    @ApiResponse(
            responseCode = "200",
            description = "The file -- a header row on its own when there is nothing to export")
    public ResponseEntity<byte[]> exportCsv() {
        return CsvDownload.export("expense-items-%s.csv".formatted(LocalDate.now()), service.exportCsv());
    }
}
