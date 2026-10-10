package com.stockmarketanalyzer.backend.instrument.domain;

import com.stockmarketanalyzer.backend.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A tradable instrument (equity, ETF, index, ...) identified by its ticker symbol.
 */
@Entity
@Table(name = "instrument")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class Instrument extends AuditableEntity {

    @Column(name = "symbol", nullable = false, length = 32, unique = true)
    @Setter(AccessLevel.NONE) // hand-written below so the symbol is always normalised
    @ToString.Include
    private String symbol;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "exchange", length = 64)
    private String exchange;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 32)
    @ToString.Include
    private AssetType assetType;

    @Column(name = "sector", length = 128)
    private String sector;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    public Instrument(String symbol, String name, String exchange, String currency, AssetType assetType) {
        this.symbol = normaliseSymbol(symbol);
        this.name = name;
        this.exchange = exchange;
        this.currency = currency;
        this.assetType = assetType;
    }

    public static String normaliseSymbol(String symbol) {
        return symbol == null ? null : symbol.trim().toUpperCase();
    }

    public void setSymbol(String symbol) {
        this.symbol = normaliseSymbol(symbol);
    }
}
