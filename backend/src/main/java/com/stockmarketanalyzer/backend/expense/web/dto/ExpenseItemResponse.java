package com.stockmarketanalyzer.backend.expense.web.dto;

import com.stockmarketanalyzer.backend.expense.domain.ExpenseItem;
import com.stockmarketanalyzer.backend.expense.domain.RecurrencePeriod;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * A recurring expense as stored, plus the one derived flag that cannot sensibly be computed
 * anywhere else.
 *
 * <p>There is no monthly-equivalent field here on purpose. Turning "every 2 weeks" into a figure
 * per month needs an average month, and an average month is a presentation choice -- so the number
 * is worked out where it is displayed, against whatever period the user has asked to see, rather
 * than baked into the API as one opinion. {@code active} is different: it is a fact about two
 * stored dates and today, and having both ends decide it the same way matters.
 */
@Schema(name = "ExpenseItemResponse", description = "A recurring expense, exactly as it was entered.")
public record ExpenseItemResponse(
        Long id,
        @Schema(description = "What the expense is called.", example = "Car loan") String name,
        @Schema(description = "What one occurrence costs.", example = "84500.000000") BigDecimal amount,
        @Schema(description = "Currency it is paid in.", example = "HUF") String unit,
        @Schema(description = "How many periods between charges.", example = "1") Integer recurrenceInterval,
        @Schema(description = "The period the interval counts.", example = "MONTHLY")
                RecurrencePeriod recurrencePeriod,
        @Schema(description = "When the charges began, if recorded.", example = "2025-03-15") LocalDate startDate,
        @Schema(description = "Last date it is charged, if it ends.", example = "2031-03-15") LocalDate expiryDate,
        @Schema(description = "The tags it carries, personal and global, in alphabetical order.")
                List<ExpenseCategoryResponse> categories,
        @Schema(
                        description =
                                "True when it is being charged today: started on or before today and not "
                                        + "expired. A row with neither date is always active.",
                        example = "true")
                boolean active) {

    public static ExpenseItemResponse from(ExpenseItem item, LocalDate today) {
        return new ExpenseItemResponse(
                item.getId(),
                item.getName(),
                item.getAmount(),
                item.getUnit(),
                item.getRecurrenceInterval(),
                item.getRecurrencePeriod(),
                item.getStartDate(),
                item.getExpiryDate(),
                // The collection is joined in by the entity graph, so this does not lazy-load.
                // Sorted here as well as in the mapping: @OrderBy settles the database's order,
                // and a set built by hand on a write path has none until it is read back.
                item.getCategories().stream()
                        .sorted(Comparator.comparing(
                                category -> category.getCategory().toLowerCase()))
                        .map(ExpenseCategoryResponse::from)
                        .toList(),
                isActive(item, today));
    }

    /**
     * A missing date is an open end of the interval, not missing data: no start means it has
     * always been charged, no expiry means it still is.
     */
    private static boolean isActive(ExpenseItem item, LocalDate today) {
        boolean started = item.getStartDate() == null || !item.getStartDate().isAfter(today);
        boolean unexpired = item.getExpiryDate() == null || !item.getExpiryDate().isBefore(today);
        return started && unexpired;
    }
}
