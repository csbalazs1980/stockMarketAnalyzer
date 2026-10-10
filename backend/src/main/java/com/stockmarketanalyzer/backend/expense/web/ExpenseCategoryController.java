package com.stockmarketanalyzer.backend.expense.web;

import com.stockmarketanalyzer.backend.common.PageResponse;
import com.stockmarketanalyzer.backend.common.csv.CsvDownload;
import com.stockmarketanalyzer.backend.common.csv.CsvImportResult;
import com.stockmarketanalyzer.backend.expense.service.ExpenseCategoryService;
import com.stockmarketanalyzer.backend.expense.web.dto.ExpenseCategoryRequest;
import com.stockmarketanalyzer.backend.expense.web.dto.ExpenseCategoryResponse;
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

/**
 * Categories behave like tags, and come in two kinds in one table: the caller's own, and global
 * ones everybody shares. Reads return both; only an administrator may write a global row, which is
 * checked in the service rather than here -- the path has to stay open to an ordinary {@code USER}
 * writing their own.
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/expenses/categories")
@Tag(
        name = "Expense categories",
        description =
                "Tags for recurring expenses. Your own plus the global ones; creating a global one "
                        + "needs the ADMIN role.")
public class ExpenseCategoryController {

    private static final String PROBLEM_JSON = "application/problem+json";

    private final ExpenseCategoryService service;

    @GetMapping
    @Operation(
            summary = "List expense categories",
            description = "Your own and the global ones, alphabetically. Use 'global' to narrow to one kind.")
    @ApiResponse(responseCode = "200", description = "Categories returned (possibly empty)")
    public PageResponse<ExpenseCategoryResponse> list(
            @Parameter(description = "Case-insensitive substring of the name.", example = "sub")
                    @RequestParam(required = false)
                    String search,
            @Parameter(
                            description =
                                    "Omit for both kinds; true for global categories only, false for "
                                            + "your own only.",
                            example = "true")
                    @RequestParam(required = false)
                    Boolean global,
            @PageableDefault(size = 200, sort = "category", direction = Sort.Direction.ASC) Pageable pageable) {
        return PageResponse.of(service.search(search, global, pageable), ExpenseCategoryResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one category")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Category found"),
        @ApiResponse(
                responseCode = "404",
                description = "No such category, or it is somebody else's",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ExpenseCategoryResponse get(@PathVariable Long id) {
        return ExpenseCategoryResponse.from(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Create a category",
            description =
                    "Personal by default. Set 'global' to share it with everybody, which requires the "
                            + "ADMIN role. A name you -- or the installation -- already have is a 409.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Category stored"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "403",
                description = "A global category was asked for without the ADMIN role",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "A category of that name already exists, as yours or as a global one",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ExpenseCategoryResponse create(@RequestBody @Valid ExpenseCategoryRequest request) {
        return ExpenseCategoryResponse.from(service.create(request));
    }

    @PutMapping("/{id}")
    @Operation(
            summary = "Rename a category",
            description =
                    "Renaming only. A category cannot be moved between personal and global: one would "
                            + "hand your label to everybody, the other would strip it from everybody.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Category renamed"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed, or 'global' does not match the stored row",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "403",
                description = "A global category was edited without the ADMIN role",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "No such category, or it is somebody else's",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Another category already has that name",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ExpenseCategoryResponse update(
            @PathVariable Long id, @RequestBody @Valid ExpenseCategoryRequest request) {
        return ExpenseCategoryResponse.from(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Delete a category",
            description =
                    "A category still carried by expense items is a 409 unless force=true, which "
                            + "untags them. The expenses themselves are never touched.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Category deleted"),
        @ApiResponse(
                responseCode = "403",
                description = "A global category was deleted without the ADMIN role",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "No such category, or it is somebody else's",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "The category is in use and force was not set",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public void delete(
            @PathVariable Long id,
            @Parameter(description = "Untag the items using it instead of refusing.", example = "false")
                    @RequestParam(required = false, defaultValue = "false")
                    boolean force) {
        service.delete(id, force);
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Import categories from a CSV file",
            description =
                    "One column, 'category': a name per line. Whether the file lands as global or "
                            + "personal is decided once, by the 'global' field, rather than per row -- "
                            + "and setting it requires the ADMIN role. Names you or the installation "
                            + "already have are counted as duplicates and skipped, so re-uploading an "
                            + "overlapping list is safe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The file was read; the counts say what happened"),
        @ApiResponse(
                responseCode = "400",
                description = "Not a CSV file, empty, or missing the 'category' column -- nothing was stored",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "403",
                description = "global=true without the ADMIN role",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CsvImportResult importCsv(
            @Parameter(description = "A .csv file with a 'category' column.") @RequestParam("file")
                    MultipartFile file,
            @Parameter(
                            description =
                                    "True to store every row as a global category. ADMIN only; "
                                            + "everybody else imports personal categories.",
                            example = "false")
                    @RequestParam(required = false, defaultValue = "false")
                    boolean global) {
        return service.importCsv(file, global);
    }

    @GetMapping(path = "/import/sample", produces = "text/csv")
    @Operation(summary = "Download a sample CSV", description = "The header row and three example names.")
    @ApiResponse(responseCode = "200", description = "The sample file")
    public ResponseEntity<byte[]> importSample() {
        return CsvDownload.sample("expense-categories-sample.csv", ExpenseCategoryService.CSV_SAMPLE);
    }

    @GetMapping(path = "/export", produces = "text/csv")
    @Operation(
            summary = "Download the caller's categories as CSV",
            description =
                    "The caller's own tags, one per line, in the shape the import reads. Global categories "
                            + "are left out: they belong to everybody rather than to the caller, and importing them "
                            + "elsewhere would only create personal duplicates of tags that account already sees.")
    @ApiResponse(
            responseCode = "200",
            description = "The file -- a header row on its own when there is nothing to export")
    public ResponseEntity<byte[]> exportCsv() {
        return CsvDownload.export("expense-categories-%s.csv".formatted(LocalDate.now()), service.exportCsv());
    }
}
