package com.stockmarketanalyzer.backend.expense.web.dto;

import com.stockmarketanalyzer.backend.expense.domain.ExpenseCategory;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A category as the caller sees it.
 *
 * <p>{@code ownerSub} is deliberately not exposed -- the only thing a client needs to know about
 * it is whether the row is global, because that is what decides whether the row can be edited.
 */
@Schema(name = "ExpenseCategoryResponse", description = "An expense category, personal or global.")
public record ExpenseCategoryResponse(
        Long id,
        @Schema(description = "The label.", example = "Subscription") String category,
        @Schema(
                        description =
                                "True for a shared category. Global rows are visible to everybody and "
                                        + "editable only by an administrator.",
                        example = "false")
                boolean global) {

    public static ExpenseCategoryResponse from(ExpenseCategory category) {
        return new ExpenseCategoryResponse(category.getId(), category.getCategory(), category.isGlobal());
    }
}
