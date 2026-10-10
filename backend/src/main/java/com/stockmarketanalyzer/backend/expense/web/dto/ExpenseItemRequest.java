package com.stockmarketanalyzer.backend.expense.web.dto;

import com.stockmarketanalyzer.backend.expense.domain.RecurrencePeriod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

@Schema(
        name = "ExpenseItemRequest",
        description =
                "A recurring cost. The recurrence is an interval plus a period -- 2 + WEEKLY is "
                        + "fortnightly, 6 + MONTHLY is twice a year -- and nothing normalised is stored.")
public record ExpenseItemRequest(
        @Schema(description = "What the expense is called.", example = "Car loan") @NotBlank @Size(max = 128)
                String name,
        @Schema(description = "What one occurrence costs. Zero is allowed.", example = "84500")
                @NotNull
                @PositiveOrZero
                BigDecimal amount,
        @Schema(
                        description = "Currency it is paid in. Free-form, stored upper-cased.",
                        example = "HUF",
                        allowableValues = {"HUF", "EUR", "USD"})
                @NotBlank
                @Size(max = 16)
                String unit,
        @Schema(
                        description = "How many periods between charges. 1 for a plain monthly bill.",
                        example = "1",
                        defaultValue = "1")
                @Min(1)
                // A thousand of anything is past the point where an interval is the right model,
                // and it keeps a typo from producing a period no arithmetic can represent.
                @Max(1000)
                Integer recurrenceInterval,
        @Schema(description = "The period the interval counts.", example = "MONTHLY") @NotNull
                RecurrencePeriod recurrencePeriod,
        @Schema(
                        description =
                                "When the charges began (ISO-8601). Optional: leaving it out means "
                                        + "'this is simply what I pay', with no calendar attached.",
                        example = "2025-03-15")
                LocalDate startDate,
        @Schema(
                        description =
                                "Last date it is charged (ISO-8601). Optional, and the ordinary case is "
                                        + "that there is none -- a loan has one, a subscription does not.",
                        example = "2031-03-15")
                LocalDate expiryDate,
        @Schema(
                        description =
                                "Ids of the categories this item carries. The complete set, not a delta: "
                                        + "an absent or empty list means uncategorised. Each must be one of "
                                        + "your own categories or a global one.",
                        example = "[1, 4]")
                Set<Long> categoryIds) {

    /** Absent and empty are the same answer -- uncategorised -- so callers get one shape. */
    public Set<Long> categoryIdsOrEmpty() {
        return categoryIds == null ? Set.of() : Set.copyOf(categoryIds);
    }

    /** 1 is what "every month" means, so the field is optional in the payload. */
    public int intervalOrOne() {
        return recurrenceInterval == null ? 1 : recurrenceInterval;
    }
}
