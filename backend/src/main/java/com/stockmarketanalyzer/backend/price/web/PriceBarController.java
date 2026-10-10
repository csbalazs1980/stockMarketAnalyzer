package com.stockmarketanalyzer.backend.price.web;

import com.stockmarketanalyzer.backend.common.PageResponse;
import com.stockmarketanalyzer.backend.price.service.PriceBarService;
import com.stockmarketanalyzer.backend.price.web.dto.PriceBarRequest;
import com.stockmarketanalyzer.backend.price.web.dto.PriceBarResponse;
import com.stockmarketanalyzer.backend.price.web.dto.PriceSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/instruments/{symbol}/prices")
@Tag(name = "Prices", description = "Daily OHLCV bars for a given instrument.")
public class PriceBarController {

    private static final String PROBLEM_JSON = "application/problem+json";

    private final PriceBarService service;

    @GetMapping
    @Operation(
            summary = "List price bars",
            description = "Paged, newest first. Omitting a bound opens the range in that direction.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Bars returned (possibly empty)"),
        @ApiResponse(
                responseCode = "404",
                description = "No instrument with that symbol",
                content =
                        @Content(
                                mediaType = PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public PageResponse<PriceBarResponse> list(
            @Parameter(description = "Ticker symbol.", example = "AAPL") @PathVariable String symbol,
            @Parameter(description = "Inclusive start date (ISO-8601).", example = "2026-01-01")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @Parameter(description = "Inclusive end date (ISO-8601). Defaults to today.", example = "2026-08-14")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @PageableDefault(size = 100, sort = "tradeDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.of(service.findBars(symbol, from, to, pageable), PriceBarResponse::from);
    }

    @GetMapping("/latest")
    @Operation(summary = "Latest price bar", description = "The most recent bar stored for the instrument.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Bar found"),
        @ApiResponse(
                responseCode = "404",
                description = "Unknown symbol, or no price data stored yet",
                content =
                        @Content(
                                mediaType = PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public PriceBarResponse latest(
            @Parameter(description = "Ticker symbol.", example = "AAPL") @PathVariable String symbol) {
        return PriceBarResponse.from(service.findLatest(symbol));
    }

    @GetMapping("/summary")
    @Operation(
            summary = "Aggregate price statistics",
            description = "Bar count, lowest low, highest high and mean close over the requested range.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Statistics computed"),
        @ApiResponse(
                responseCode = "404",
                description = "No instrument with that symbol",
                content =
                        @Content(
                                mediaType = PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public PriceSummaryResponse summary(
            @Parameter(description = "Ticker symbol.", example = "AAPL") @PathVariable String symbol,
            @Parameter(description = "Inclusive start date (ISO-8601).", example = "2026-01-01")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @Parameter(description = "Inclusive end date (ISO-8601). Defaults to today.", example = "2026-08-14")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to) {
        return PriceSummaryResponse.from(symbol, from, to, service.statistics(symbol, from, to));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Bulk upsert price bars",
            description =
                    "Accepts an array of bars. A bar already stored for the same trading day is replaced, "
                            + "so re-sending a provider's payload is idempotent.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Bars stored"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed, or high < low, or the trade date is in the future",
                content =
                        @Content(
                                mediaType = PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "No instrument with that symbol",
                content =
                        @Content(
                                mediaType = PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<PriceBarResponse> upsert(
            @Parameter(description = "Ticker symbol.", example = "AAPL") @PathVariable String symbol,
            @RequestBody @NotEmpty List<@Valid PriceBarRequest> bars) {
        return service.upsert(symbol, bars).stream().map(PriceBarResponse::from).toList();
    }

    @DeleteMapping
    @Operation(
            summary = "Delete a date range of price bars",
            description = "Returns the number of rows removed. Omitting both bounds clears the whole history.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Rows deleted"),
        @ApiResponse(
                responseCode = "404",
                description = "No instrument with that symbol",
                content =
                        @Content(
                                mediaType = PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public Map<String, Long> deleteRange(
            @Parameter(description = "Ticker symbol.", example = "AAPL") @PathVariable String symbol,
            @Parameter(description = "Inclusive start date (ISO-8601).", example = "2026-01-01")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @Parameter(description = "Inclusive end date (ISO-8601). Defaults to today.", example = "2026-08-14")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to) {
        return Map.of("deleted", service.deleteRange(symbol, from, to));
    }
}
