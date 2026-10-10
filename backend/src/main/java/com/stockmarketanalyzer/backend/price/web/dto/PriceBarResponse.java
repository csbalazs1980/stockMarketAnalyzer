package com.stockmarketanalyzer.backend.price.web.dto;

import com.stockmarketanalyzer.backend.price.domain.PriceBar;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(name = "PriceBarResponse", description = "A stored daily OHLCV bar.")
public record PriceBarResponse(
        Long id,
        String symbol,
        LocalDate tradeDate,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal adjustedClose,
        Long volume) {

    public static PriceBarResponse from(PriceBar bar) {
        return new PriceBarResponse(
                bar.getId(),
                bar.getInstrument().getSymbol(),
                bar.getTradeDate(),
                bar.getOpen(),
                bar.getHigh(),
                bar.getLow(),
                bar.getClose(),
                bar.getAdjustedClose(),
                bar.getVolume());
    }
}
