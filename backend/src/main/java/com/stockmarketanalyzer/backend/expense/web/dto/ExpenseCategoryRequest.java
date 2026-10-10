package com.stockmarketanalyzer.backend.expense.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(
        name = "ExpenseCategoryRequest",
        description =
                "A tag for expense items. The owner is taken from the bearer token; 'global' asks "
                        + "for a shared category instead, which only an administrator may do.")
public record ExpenseCategoryRequest(
        @Schema(description = "The label, stored as typed. Compared case-insensitively.", example = "Subscription")
                @NotBlank
                @Size(max = 64)
                String category,
        @Schema(
                        description =
                                "True for a category everybody can use. Requires the ADMIN role; an "
                                        + "ordinary user asking for one is a 403, not a silent personal tag.",
                        example = "false",
                        defaultValue = "false")
                Boolean global) {

    /** Absent means personal: the safe reading, and what every non-administrator sends. */
    public boolean isGlobal() {
        return Boolean.TRUE.equals(global);
    }
}
