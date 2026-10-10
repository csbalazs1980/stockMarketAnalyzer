package com.stockmarketanalyzer.backend.dividend.domain;

import com.stockmarketanalyzer.backend.common.AuditableEntity;
import com.stockmarketanalyzer.backend.common.Units;
import com.stockmarketanalyzer.backend.instrument.domain.Instrument;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A dividend the owner was actually paid: {@code amount} of {@code unit}, on {@code paymentDate},
 * by {@code instrument}.
 *
 * <p>Nothing about worth is stored. Like {@code WealthEntry}, the amount is converted on read
 * from the owner's own exchange rates, so correcting a rate re-values the whole history rather
 * than leaving stale numbers behind.
 *
 * <p>The unit is kept separately from {@code instrument.currency} on purpose: the instrument's
 * currency is what it trades in, whereas this is what actually landed in the account -- a
 * broker paying a US dividend into a HUF account is the normal case, not an anomaly.
 *
 * <p>As elsewhere, the lazy association is kept out of {@code toString()}; id and equality come
 * from {@link com.stockmarketanalyzer.backend.common.BaseEntity}.
 */
@Entity
@Table(name = "dividend")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class Dividend extends AuditableEntity {

    /** Keycloak {@code sub} of the owner. Never taken from the request body. */
    @Column(name = "owner_sub", nullable = false, length = 64)
    @Setter(AccessLevel.NONE)
    private String ownerSub;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "instrument_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_dividend_instrument"))
    private Instrument instrument;

    /** What was received. Always a real figure -- there is no aggregate row here. */
    @Column(name = "amount", nullable = false, precision = 24, scale = 6)
    @ToString.Include
    private BigDecimal amount;

    /** Free-form unit of measure, normalised to upper case: USD, HUF, EUR, ... */
    @Column(name = "unit", nullable = false, length = 16)
    @Setter(AccessLevel.NONE) // hand-written below so the unit is always normalised
    @ToString.Include
    private String unit;

    @Column(name = "payment_date", nullable = false)
    @ToString.Include
    private LocalDate paymentDate;

    public Dividend(String ownerSub, Instrument instrument, BigDecimal amount, String unit, LocalDate paymentDate) {
        this.ownerSub = ownerSub;
        this.instrument = instrument;
        this.amount = amount;
        this.unit = Units.normalise(unit);
        this.paymentDate = paymentDate;
    }

    public void setUnit(String unit) {
        this.unit = Units.normalise(unit);
    }
}
