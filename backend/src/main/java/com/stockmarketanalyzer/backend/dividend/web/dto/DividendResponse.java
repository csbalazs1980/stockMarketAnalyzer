package com.stockmarketanalyzer.backend.dividend.web.dto;

import com.stockmarketanalyzer.backend.dividend.domain.Dividend;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(name = "DividendResponse", description = "A dividend that was received, valued on read.")
public record DividendResponse(
        Long id,
        @Schema(description = "Id of the paying instrument.", example = "1") Long instrumentId,
        @Schema(description = "Ticker of the paying instrument.", example = "AAPL") String instrumentSymbol,
        @Schema(description = "Name of the paying instrument.", example = "Apple Inc.") String instrumentName,
        @Schema(description = "How much was received, as entered.", example = "42.75") BigDecimal amount,
        @Schema(description = "Currency it was paid in.", example = "USD") String unit,
        LocalDate paymentDate,
        @Schema(
                        description =
                                "amount converted into valueUnit using the rate in force on paymentDate. "
                                        + "Null when no rate connects the units -- never zero.",
                        example = "13423.500")
                BigDecimal currentValue,
        @Schema(description = "Unit currentValue is expressed in.", example = "HUF") String valueUnit,
        @Schema(
                        description =
                                "Only with inflationAdjusted=true, otherwise null: amount and currentValue in the "
                                        + "money of the latest year with an inflation figure. amount and currentValue "
                                        + "themselves always stay as stored, so a client that edits a row never "
                                        + "saves an adjusted figure back.")
                InflationAdjusted inflationAdjusted) {

    @Schema(name = "DividendInflationAdjusted")
    public record InflationAdjusted(
            @Schema(
                            description =
                                    "amount times the cumulative inflation of its own currency from the payment "
                                            + "year on. The amount as entered when that cannot be computed.",
                            example = "44.32")
                    BigDecimal amount,
            @Schema(
                            description =
                                    "currentValue (converted at the payment date's rate first) times the cumulative "
                                            + "inflation of valueUnit. Null exactly when currentValue is.",
                            example = "14100.25")
                    BigDecimal currentValue,
            @Schema(
                            description =
                                    "False when either figure was left unadjusted for want of inflation data.",
                            example = "true")
                    boolean complete) {}

    public static DividendResponse from(Dividend dividend, BigDecimal currentValue, String valueUnit) {
        return from(dividend, currentValue, valueUnit, null);
    }

    public static DividendResponse from(
            Dividend dividend, BigDecimal currentValue, String valueUnit, InflationAdjusted inflationAdjusted) {
        return new DividendResponse(
                dividend.getId(),
                // The instrument is joined in by the entity graph, so these do not lazy-load.
                dividend.getInstrument().getId(),
                dividend.getInstrument().getSymbol(),
                dividend.getInstrument().getName(),
                dividend.getAmount(),
                dividend.getUnit(),
                dividend.getPaymentDate(),
                currentValue,
                valueUnit,
                inflationAdjusted);
    }
}
