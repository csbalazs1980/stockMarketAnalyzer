package com.stockmarketanalyzer.backend.wealth.web;

import com.stockmarketanalyzer.backend.common.PageResponse;
import com.stockmarketanalyzer.backend.wealth.domain.WealthEntry;
import com.stockmarketanalyzer.backend.wealth.service.EntryValuation;
import com.stockmarketanalyzer.backend.wealth.service.WealthEntryService;
import com.stockmarketanalyzer.backend.wealth.web.dto.WealthEntryCloneRequest;
import com.stockmarketanalyzer.backend.wealth.web.dto.WealthEntryRequest;
import com.stockmarketanalyzer.backend.wealth.web.dto.WealthEntryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/wealth/entries")
@Tag(
        name = "Wealth",
        description =
                "Personal wealth snapshots: what you hold, in what unit, on what date. "
                        + "Every operation is scoped to the caller's own data.")
public class WealthEntryController {

    private static final String PROBLEM_JSON = "application/problem+json";

    private final WealthEntryService service;

    @GetMapping
    @Operation(
            summary = "List wealth entries",
            description =
                    "Paged, newest snapshot first. Every filter is optional; each row is valued in "
                            + "'valueIn' using the rate in force on its own date.")
    @ApiResponse(responseCode = "200", description = "Entries returned (possibly empty)")
    public PageResponse<WealthEntryResponse> list(
            @Parameter(description = "Inclusive earliest snapshot date.", example = "2026-01-01")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @Parameter(description = "Inclusive latest snapshot date.", example = "2026-08-31")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @Parameter(description = "Only entries held in this unit.", example = "USD")
                    @RequestParam(required = false)
                    String unit,
            @Parameter(description = "Only the direct children of this entry.", example = "12")
                    @RequestParam(required = false)
                    Long parentId,
            @Parameter(description = "Only entries with no parent.", example = "false")
                    @RequestParam(defaultValue = "false")
                    boolean rootsOnly,
            @Parameter(description = "Case-insensitive match on the entry name.", example = "tbsz")
                    @RequestParam(required = false)
                    String search,
            @Parameter(description = "Unit to value the entries in. Defaults to HUF.", example = "HUF")
                    @RequestParam(required = false)
                    String valueIn,
            @PageableDefault(size = 100, sort = "entryDate", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<WealthEntry> page = service.search(from, to, unit, parentId, rootsOnly, search, pageable);
        String target = WealthEntryService.resolveValueUnit(valueIn);
        Map<Long, EntryValuation> valuations = service.valuations(page.getContent(), target);
        return PageResponse.of(
                page, entry -> WealthEntryResponse.from(entry, valuations.get(entry.getId()), target));
    }

    @GetMapping("/latest")
    @Operation(
            summary = "Latest snapshot",
            description = "Every entry recorded on the most recent snapshot date, oldest row first.")
    @ApiResponse(responseCode = "200", description = "Snapshot returned (empty when nothing is stored yet)")
    public List<WealthEntryResponse> latest(
            @Parameter(description = "Unit to value the entries in. Defaults to HUF.", example = "HUF")
                    @RequestParam(required = false)
                    String valueIn) {
        return valued(service.latestSnapshot(), valueIn);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one wealth entry")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Entry found"),
        @ApiResponse(
                responseCode = "404",
                description = "No such entry, or it belongs to someone else",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public WealthEntryResponse get(
            @PathVariable Long id,
            @Parameter(description = "Unit to value the entry in. Defaults to HUF.", example = "HUF")
                    @RequestParam(required = false)
                    String valueIn) {
        WealthEntry entry = service.get(id);
        String target = WealthEntryService.resolveValueUnit(valueIn);
        return WealthEntryResponse.from(entry, service.valuation(entry, target), target);
    }

    @GetMapping("/{id}/children")
    @Operation(summary = "Direct children of an entry", description = "The rows that roll up into this one.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Children returned (possibly empty)"),
        @ApiResponse(
                responseCode = "404",
                description = "No such entry, or it belongs to someone else",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<WealthEntryResponse> children(
            @PathVariable Long id,
            @Parameter(description = "Unit to value the entries in. Defaults to HUF.", example = "HUF")
                    @RequestParam(required = false)
                    String valueIn) {
        return valued(service.children(id), valueIn);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Create a wealth entry",
            description = "The entry is owned by the caller; ownership cannot be set from the payload.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Entry stored"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "parentId does not resolve to one of the caller's entries",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public WealthEntryResponse create(@RequestBody @Valid WealthEntryRequest request) {
        WealthEntry entry = service.create(request);
        String target = WealthEntryService.DEFAULT_VALUE_UNIT;
        return WealthEntryResponse.from(entry, service.valuation(entry, target), target);
    }

    @PostMapping("/{id}/clone")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Clone an entry and everything under it",
            description =
                    "Copies the subtree into a new snapshot in a single transaction: same shape, same "
                            + "names, one new date, and whatever quantities and units the overrides supply. "
                            + "The copy hangs off the same parent as the source. Returns the new rows, "
                            + "root first.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Subtree copied"),
        @ApiResponse(
                responseCode = "400",
                description = "An override names an entry outside the cloned subtree, or fails validation",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "No such entry, or it belongs to someone else",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<WealthEntryResponse> cloneSubtree(
            @PathVariable Long id,
            @RequestBody @Valid WealthEntryCloneRequest request,
            @Parameter(description = "Unit to value the new entries in. Defaults to HUF.", example = "HUF")
                    @RequestParam(required = false)
                    String valueIn) {
        return valued(service.cloneSubtree(id, request), valueIn);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace a wealth entry")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Entry updated"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed, or the new parent would create a cycle",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "No such entry, or it belongs to someone else",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public WealthEntryResponse update(@PathVariable Long id, @RequestBody @Valid WealthEntryRequest request) {
        WealthEntry entry = service.update(id, request);
        String target = WealthEntryService.DEFAULT_VALUE_UNIT;
        return WealthEntryResponse.from(entry, service.valuation(entry, target), target);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Delete a wealth entry",
            description = "An entry with children is refused unless cascade=true, which removes the subtree.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Entry deleted"),
        @ApiResponse(
                responseCode = "404",
                description = "No such entry, or it belongs to someone else",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "The entry has children and cascade was not requested",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public void delete(
            @PathVariable Long id,
            @Parameter(description = "Also delete every descendant.", example = "false")
                    @RequestParam(defaultValue = "false")
                    boolean cascade) {
        service.delete(id, cascade);
    }

    private List<WealthEntryResponse> valued(Collection<WealthEntry> entries, String valueIn) {
        String target = WealthEntryService.resolveValueUnit(valueIn);
        Map<Long, EntryValuation> valuations = service.valuations(entries, target);
        return entries.stream()
                .map(entry -> WealthEntryResponse.from(entry, valuations.get(entry.getId()), target))
                .toList();
    }
}
