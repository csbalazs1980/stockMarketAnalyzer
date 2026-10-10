package com.stockmarketanalyzer.backend.wealth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(
        name = "ExchangeRateRequest",
        description = "One conversion factor as of a date. Re-posting the same pair and date replaces it.")
public record ExchangeRateRequest(
        @Schema(description = "Unit being converted from.", example = "USD") @NotBlank @Size(max = 16)
                String sourceUnit,
        @Schema(description = "Unit being converted to.", example = "HUF") @NotBlank @Size(max = 16)
                String destinationUnit,
        @Schema(description = "How many destinationUnit one sourceUnit is worth.", example = "314")
                @NotNull
                @Positive
                BigDecimal rate,
        @Schema(description = "Date the quote applies from (ISO-8601). Defaults to today.", example = "2026-08-05")
                LocalDate rateDate,
        @Schema(
                        description =
                                "True for a rate every user is valued from. Requires the ADMIN role; an "
                                        + "ordinary user asking for one is a 403, not a silent personal rate.",
                        example = "false",
                        defaultValue = "false")
                Boolean global) {

    /** A personal rate -- what every caller without the ADMIN role sends. */
    public ExchangeRateRequest(String sourceUnit, String destinationUnit, BigDecimal rate, LocalDate rateDate) {
        this(sourceUnit, destinationUnit, rate, rateDate, null);
    }

    /** Absent means personal: the safe reading. */
    public boolean isGlobal() {
        return Boolean.TRUE.equals(global);
    }
}
