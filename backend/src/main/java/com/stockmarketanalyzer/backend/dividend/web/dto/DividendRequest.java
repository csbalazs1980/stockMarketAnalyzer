package com.stockmarketanalyzer.backend.dividend.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(
        name = "DividendRequest",
        description =
                "A dividend payment received. The owner is taken from the bearer token, and the "
                        + "value in HUF/USD is derived from the exchange rates rather than sent in.")
public record DividendRequest(
        @Schema(description = "Id of the instrument that paid it.", example = "1") @NotNull Long instrumentId,
        @Schema(description = "How much was received, before any conversion.", example = "42.75")
                @NotNull
                @PositiveOrZero
                BigDecimal amount,
        @Schema(
                        description = "Currency it was actually paid in. Free-form, stored upper-cased.",
                        example = "USD",
                        allowableValues = {"USD", "HUF", "EUR"})
                @NotBlank
                @Size(max = 16)
                String unit,
        @Schema(description = "Payment date (ISO-8601). Defaults to today.", example = "2026-08-15")
                LocalDate paymentDate) {}
