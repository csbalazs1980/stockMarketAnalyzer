package com.stockmarketanalyzer.backend.instrument.web.dto;

import com.stockmarketanalyzer.backend.instrument.domain.AssetType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(name = "InstrumentRequest", description = "Payload for creating or replacing an instrument.")
public record InstrumentRequest(
        @Schema(description = "Ticker symbol. Stored upper-cased.", example = "AAPL", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Size(max = 32)
                @Pattern(regexp = "[A-Za-z0-9.\\-^:]+", message = "must be a valid ticker symbol")
                String symbol,
        @Schema(description = "Display name.", example = "Apple Inc.", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Size(max = 255)
                String name,
        @Schema(description = "Listing venue.", example = "NASDAQ") @Size(max = 64) String exchange,
        @Schema(
                        description = "ISO 4217 currency code. Stored upper-cased.",
                        example = "USD",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter ISO 4217 code")
                String currency,
        @Schema(description = "Instrument class.", example = "STOCK", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                AssetType assetType,
        @Schema(description = "Free-text sector label.", example = "Technology") @Size(max = 128) String sector,
        @Schema(description = "Defaults to true when omitted.", example = "true") Boolean active) {}
