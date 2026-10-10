package com.stockmarketanalyzer.backend.price.web.dto;

import com.stockmarketanalyzer.backend.price.repository.PriceStatistics;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Schema(name = "PriceSummaryResponse", description = "Aggregate price statistics over a date range.")
public record PriceSummaryResponse(
        String symbol,
        LocalDate from,
        LocalDate to,
        long barCount,
        BigDecimal lowest,
        BigDecimal highest,
        BigDecimal averageClose,
        LocalDate firstTradeDate,
        LocalDate lastTradeDate) {

    public static PriceSummaryResponse from(String symbol, LocalDate from, LocalDate to, PriceStatistics stats) {
        BigDecimal average = stats == null || stats.averageClose() == null
                ? null
                : BigDecimal.valueOf(stats.averageClose()).setScale(6, RoundingMode.HALF_UP);
        return new PriceSummaryResponse(
                symbol,
                from,
                to,
                stats == null ? 0L : stats.barCount(),
                stats == null ? null : stats.lowest(),
                stats == null ? null : stats.highest(),
                average,
                stats == null ? null : stats.firstTradeDate(),
                stats == null ? null : stats.lastTradeDate());
    }
}
