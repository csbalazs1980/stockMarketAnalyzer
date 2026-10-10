package com.stockmarketanalyzer.backend.wealth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(
        name = "WealthEntryRequest",
        description =
                "One line of a wealth snapshot. The owner is taken from the bearer token, and the "
                        + "value in HUF/USD is derived from the exchange rates rather than sent in.")
public record WealthEntryRequest(
        @Schema(description = "Label for the row.", example = "tbsz25") @NotBlank @Size(max = 128) String name,
        @Schema(
                        description = "Unit of measure. Free-form, stored upper-cased.",
                        example = "USD",
                        allowableValues = {"USD", "HUF", "OZ", "KG"})
                @NotBlank
                @Size(max = 16)
                String unit,
        @Schema(
                        description =
                                "How much of the unit is held. Omit it for an aggregate row that only "
                                        + "rolls its children up; null is not the same as zero.",
                        example = "27595")
                @PositiveOrZero
                BigDecimal quantity,
        @Schema(description = "Snapshot date (ISO-8601). Defaults to today.", example = "2026-08-05")
                LocalDate entryDate,
        @Schema(
                        description =
                                "Id of the entry this one rolls up into, e.g. the savings aggregate. "
                                        + "Must belong to the caller. Null for a top-level row.",
                        example = "12")
                Long parentId) {}
