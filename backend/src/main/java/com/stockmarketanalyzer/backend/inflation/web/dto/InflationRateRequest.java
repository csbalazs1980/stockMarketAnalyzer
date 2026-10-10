package com.stockmarketanalyzer.backend.inflation.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Schema(
        name = "InflationRateRequest",
        description = "Annual inflation for one currency. Re-posting the same currency and year replaces it.")
public record InflationRateRequest(
        @Schema(description = "Currency the figure applies to. Stored upper-cased.", example = "HUF")
                @NotBlank
                @Size(max = 16)
                String unit,
        @Schema(description = "Calendar year the rate applies to.", example = "2025")
                @NotNull
                @Min(1900)
                @Max(2200)
                Integer year,
        @Schema(
                        description =
                                "Percentage points -- 4.6 means 4.6%. Negative values are deflation, "
                                        + "and nothing can fall by more than 100%. Stored to four decimal "
                                        + "places; the column holds at most 99999.9999.",
                        example = "4.6")
                @NotNull
                @DecimalMin("-100.0")
                @DecimalMax("99999.9999")
                BigDecimal rate) {}
