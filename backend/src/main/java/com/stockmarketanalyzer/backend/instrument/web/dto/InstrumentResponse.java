package com.stockmarketanalyzer.backend.instrument.web.dto;

import com.stockmarketanalyzer.backend.instrument.domain.AssetType;
import com.stockmarketanalyzer.backend.instrument.domain.Instrument;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(name = "InstrumentResponse", description = "A tradable instrument as stored by the service.")
public record InstrumentResponse(
        Long id,
        String symbol,
        String name,
        String exchange,
        String currency,
        AssetType assetType,
        String sector,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public static InstrumentResponse from(Instrument instrument) {
        return new InstrumentResponse(
                instrument.getId(),
                instrument.getSymbol(),
                instrument.getName(),
                instrument.getExchange(),
                instrument.getCurrency(),
                instrument.getAssetType(),
                instrument.getSector(),
                instrument.isActive(),
                instrument.getCreatedAt(),
                instrument.getUpdatedAt());
    }
}
