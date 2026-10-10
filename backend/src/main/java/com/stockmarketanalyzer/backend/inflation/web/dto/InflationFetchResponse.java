package com.stockmarketanalyzer.backend.inflation.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(
        name = "InflationFetchResponse",
        description =
                "What one press of the fetch button stored. For each configured currency only the years "
                        + "after the latest one already held are added -- every published year when none is.")
public record InflationFetchResponse(
        @Schema(description = "Where the figures came from.", example = "Eurostat prc_hicp_ainr") String source,
        @Schema(description = "Rows added across all currencies.", example = "3") int stored,
        @Schema(description = "One entry per configured currency.") List<UnitResult> units) {

    @Schema(name = "InflationFetchUnitResult")
    public record UnitResult(
            @Schema(description = "The application's currency.", example = "HUF") String unit,
            @Schema(description = "The Eurostat geo code it was read from.", example = "HU") String geo,
            @Schema(description = "Latest year held before the fetch; null when there was none.", example = "2023")
                    Integer previousLatestYear,
            @Schema(description = "Latest year the source publishes; null when it publishes none.", example = "2025")
                    Integer latestAvailableYear,
            @Schema(description = "The rows added, oldest first.") List<InflationRateResponse> stored) {}
}
