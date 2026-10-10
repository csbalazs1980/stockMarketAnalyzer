package com.stockmarketanalyzer.backend.price.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(
        name = "PriceBarRequest",
        description = "One daily OHLCV bar. Upserts are keyed on tradeDate, so re-sending a day replaces it.")
public record PriceBarRequest(
        @Schema(description = "Trading day (ISO-8601). Must not be in the future.", example = "2026-08-13")
                @NotNull
                LocalDate tradeDate,
        @Schema(description = "Opening price.", example = "214.50") @NotNull @PositiveOrZero BigDecimal open,
        @Schema(description = "Session high. Must be >= low.", example = "219.00") @NotNull @PositiveOrZero
                BigDecimal high,
        @Schema(description = "Session low.", example = "213.20") @NotNull @PositiveOrZero BigDecimal low,
        @Schema(description = "Closing price.", example = "218.40") @NotNull @PositiveOrZero BigDecimal close,
        @Schema(description = "Split/dividend adjusted close. Defaults to close when omitted.", example = "218.40")
                @PositiveOrZero
                BigDecimal adjustedClose,
        @Schema(description = "Shares traded.", example = "54210000") @PositiveOrZero Long volume) {}
