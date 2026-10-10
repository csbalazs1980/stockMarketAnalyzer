package com.stockmarketanalyzer.backend.wealth.domain;

import com.stockmarketanalyzer.backend.common.AuditableEntity;
import com.stockmarketanalyzer.backend.common.Units;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * How much one {@code sourceUnit} was worth in {@code destinationUnit} on {@code rateDate} --
 * 1 USD = 314 HUF, 1 OZ = 130000 HUF, and so on.
 *
 * <p>Two kinds in one table, told apart by {@code owner_sub}, exactly like expense categories: a
 * row with an owner is that person's <em>personal</em> mark, a row without one is <em>global</em>
 * and every user is valued from it. Only an administrator writes global rows. When a user has a
 * personal rate for a pair it always wins over the global one -- see
 * {@code ExchangeRateService#resolveFactor}.
 *
 * <p>Uniqueness on (owner, pair, date) is the {@code ux_exchange_rate_owner_pair_date} index from
 * changeset 0011, declared {@code NULLS NOT DISTINCT} so the global rows are constrained too. It is
 * raw SQL, so it is not repeated as a {@code @UniqueConstraint} here.
 */
@Entity
@Table(name = "exchange_rate")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class ExchangeRate extends AuditableEntity {

    /** Keycloak {@code sub} of the owner, or {@code null} for a global rate. */
    @Column(name = "owner_sub", length = 64)
    @Setter(AccessLevel.NONE)
    private String ownerSub;

    @Column(name = "source_unit", nullable = false, length = 16)
    @Setter(AccessLevel.NONE)
    @ToString.Include
    private String sourceUnit;

    @Column(name = "destination_unit", nullable = false, length = 16)
    @Setter(AccessLevel.NONE)
    @ToString.Include
    private String destinationUnit;

    @Column(name = "rate", nullable = false, precision = 24, scale = 8)
    @ToString.Include
    private BigDecimal rate;

    @Column(name = "rate_date", nullable = false)
    @ToString.Include
    private LocalDate rateDate;

    public ExchangeRate(
            String ownerSub, String sourceUnit, String destinationUnit, BigDecimal rate, LocalDate rateDate) {
        this.ownerSub = ownerSub;
        this.sourceUnit = Units.normalise(sourceUnit);
        this.destinationUnit = Units.normalise(destinationUnit);
        this.rate = rate;
        this.rateDate = rateDate;
    }

    public boolean isGlobal() {
        return ownerSub == null;
    }

    public void setSourceUnit(String sourceUnit) {
        this.sourceUnit = Units.normalise(sourceUnit);
    }

    public void setDestinationUnit(String destinationUnit) {
        this.destinationUnit = Units.normalise(destinationUnit);
    }
}
