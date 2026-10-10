package com.stockmarketanalyzer.backend.price.domain;

import com.stockmarketanalyzer.backend.common.AuditableEntity;
import com.stockmarketanalyzer.backend.instrument.domain.Instrument;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One OHLCV bar for an instrument on a given trading day.
 *
 * <p>{@code instrument} is intentionally excluded from {@code toString()} -- touching a lazy
 * association inside a log statement is a classic source of {@code LazyInitializationException}.
 */
@Entity
@Table(
        name = "price_bar",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_price_bar_instrument_date",
                        columnNames = {"instrument_id", "trade_date"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class PriceBar extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "instrument_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_price_bar_instrument"))
    private Instrument instrument;

    @Column(name = "trade_date", nullable = false)
    @ToString.Include
    private LocalDate tradeDate;

    @Column(name = "open_price", nullable = false, precision = 19, scale = 6)
    private BigDecimal open;

    @Column(name = "high_price", nullable = false, precision = 19, scale = 6)
    private BigDecimal high;

    @Column(name = "low_price", nullable = false, precision = 19, scale = 6)
    private BigDecimal low;

    @Column(name = "close_price", nullable = false, precision = 19, scale = 6)
    @ToString.Include
    private BigDecimal close;

    @Column(name = "adjusted_close", precision = 19, scale = 6)
    private BigDecimal adjustedClose;

    @Column(name = "volume")
    private Long volume;

    public PriceBar(Instrument instrument, LocalDate tradeDate) {
        this.instrument = instrument;
        this.tradeDate = tradeDate;
    }
}
