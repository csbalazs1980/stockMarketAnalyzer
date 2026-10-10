package com.stockmarketanalyzer.backend.wealth.web.dto;

import com.stockmarketanalyzer.backend.wealth.domain.ExchangeRate;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(name = "ExchangeRateResponse", description = "A stored conversion factor, personal or global.")
public record ExchangeRateResponse(
        Long id,
        String sourceUnit,
        String destinationUnit,
        BigDecimal rate,
        LocalDate rateDate,
        @Schema(description = "True for a shared rate; only an administrator may change it.") boolean global) {

    public static ExchangeRateResponse from(ExchangeRate rate) {
        return new ExchangeRateResponse(
                rate.getId(),
                rate.getSourceUnit(),
                rate.getDestinationUnit(),
                rate.getRate(),
                rate.getRateDate(),
                rate.isGlobal());
    }
}
