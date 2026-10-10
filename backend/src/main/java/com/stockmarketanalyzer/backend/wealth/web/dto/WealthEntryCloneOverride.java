package com.stockmarketanalyzer.backend.wealth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Schema(
        name = "WealthEntryCloneOverride",
        description =
                "What one row of the copy should say instead of what the source row says. An override "
                        + "replaces the row's state outright rather than patching it: a source row with no "
                        + "override is copied verbatim, and a row that has one takes the quantity given here "
                        + "even when that is null, which is how a holding becomes an aggregate.")
public record WealthEntryCloneOverride(
        @Schema(description = "Id of the row in the cloned subtree this applies to.", example = "12") @NotNull
                Long sourceId,
        @Schema(
                        description =
                                "New quantity for the copy. Null marks the copy as an aggregate row that only "
                                        + "sums its children - which is not the same as holding zero.",
                        example = "3245")
                @PositiveOrZero
                BigDecimal quantity,
        @Schema(
                        description =
                                "Unit for the copy. Leave it out or blank to inherit the unit of the copy above "
                                        + "it, so the root's unit reaches the leaves without being retyped.",
                        example = "USD")
                @Size(max = 16)
                String unit) {}
