package com.stockmarketanalyzer.backend.dividend.web.dto;

import com.stockmarketanalyzer.backend.dividend.service.DividendTotal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(
        name = "DividendSummaryResponse",
        description =
                "What a filter adds up to. Computed over every matching row, not just the page "
                        + "being displayed, so the total does not change as you page through.")
public record DividendSummaryResponse(
        @Schema(description = "How many dividends matched, convertible or not.", example = "17") long count,
        @Schema(
                        description =
                                "Sum of the convertible ones, in valueUnit. Null when none of them could "
                                        + "be converted -- which is not the same as a total of zero.",
                        example = "228199.500")
                BigDecimal total,
        @Schema(
                        description =
                                "True when at least one matching row had no rate, so total is a lower "
                                        + "bound. The UI renders it as a >= rather than a plain figure.",
                        example = "false")
                boolean totalPartial,
        @Schema(description = "Unit total is expressed in.", example = "HUF") String valueUnit,
        @Schema(description = "Whether total is inflation-adjusted (inflationAdjusted=true).", example = "false")
                boolean inflationAdjusted,
        @Schema(
                        description =
                                "With the adjustment on, how many matching rows had a figure left unadjusted for "
                                        + "want of inflation data. 0 otherwise.",
                        example = "0")
                long notInflationAdjusted) {

    public static DividendSummaryResponse from(DividendTotal total, String valueUnit) {
        return from(total, valueUnit, false);
    }

    public static DividendSummaryResponse from(DividendTotal total, String valueUnit, boolean inflationAdjusted) {
        return new DividendSummaryResponse(
                total.count(),
                total.total(),
                total.partial(),
                valueUnit,
                inflationAdjusted,
                total.notInflationAdjusted());
    }
}
