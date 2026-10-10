package com.stockmarketanalyzer.backend.wealth.web.dto;

import com.stockmarketanalyzer.backend.wealth.domain.WealthEntry;
import com.stockmarketanalyzer.backend.wealth.service.EntryValuation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(name = "WealthEntryResponse", description = "A stored wealth snapshot row, valued on read.")
public record WealthEntryResponse(
        Long id,
        String name,
        String unit,
        @Schema(
                        description =
                                "How much is held. Null marks an aggregate row that only rolls its "
                                        + "children up - which is not the same as holding zero.",
                        example = "27595")
                BigDecimal quantity,
        LocalDate entryDate,
        Long parentId,
        @Schema(
                        description =
                                "quantity converted into valueUnit using the rate in force on entryDate. "
                                        + "Null when the row holds nothing, or when no rate connects the units.",
                        example = "1564700.655")
                BigDecimal currentValue,
        @Schema(
                        description =
                                "currentValue plus the same for every descendant. This is the number to "
                                        + "show against an aggregate row.",
                        example = "4120880.120")
                BigDecimal totalValue,
        @Schema(
                        description =
                                "True when something in the subtree could not be converted, so totalValue "
                                        + "is an undercount rather than the whole answer.",
                        example = "false")
                boolean totalValuePartial,
        @Schema(
                        description =
                                "quantity plus every descendant's, but only when the whole subtree is in "
                                        + "this row's unit. Null when the subtree mixes units.",
                        example = "6165")
                BigDecimal totalQuantity,
        @Schema(description = "Unit currentValue and totalValue are expressed in.", example = "HUF")
                String valueUnit) {

    public static WealthEntryResponse from(WealthEntry entry, EntryValuation valuation, String valueUnit) {
        EntryValuation valued = valuation != null ? valuation : EntryValuation.EMPTY;
        return new WealthEntryResponse(
                entry.getId(),
                entry.getName(),
                entry.getUnit(),
                entry.getQuantity(),
                entry.getEntryDate(),
                // Reading the id off a lazy proxy does not hit the database.
                entry.getParent() != null ? entry.getParent().getId() : null,
                valued.ownValue(),
                valued.totalValue(),
                valued.totalPartial(),
                valued.totalQuantity(),
                valueUnit);
    }
}
