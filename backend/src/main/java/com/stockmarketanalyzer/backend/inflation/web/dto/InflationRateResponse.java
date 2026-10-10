package com.stockmarketanalyzer.backend.inflation.web.dto;

import com.stockmarketanalyzer.backend.inflation.domain.InflationRate;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(name = "InflationRateResponse", description = "A stored annual inflation figure, in percentage points.")
public record InflationRateResponse(Long id, String unit, Integer year, BigDecimal rate) {

    public static InflationRateResponse from(InflationRate rate) {
        return new InflationRateResponse(rate.getId(), rate.getUnit(), rate.getYear(), rate.getRate());
    }
}
