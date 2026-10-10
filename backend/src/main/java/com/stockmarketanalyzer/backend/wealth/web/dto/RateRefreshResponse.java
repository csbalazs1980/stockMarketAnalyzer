package com.stockmarketanalyzer.backend.wealth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(
        name = "RateRefreshResponse",
        description =
                "What one press of the refresh button stored. The rates carry the feed's own "
                        + "publication date, which on a weekend is the previous working day.")
public record RateRefreshResponse(
        @Schema(description = "Source the numbers were pinned to.", example = "MNB") String provider,
        @Schema(description = "Pairs the feed was asked for.", example = "3") int requested,
        @Schema(description = "Pairs it answered with, and that were stored.", example = "3") int stored,
        @Schema(description = "The stored quotes, as the list endpoint would return them.")
                List<ExchangeRateResponse> rates) {}
