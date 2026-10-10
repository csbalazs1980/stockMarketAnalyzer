package com.stockmarketanalyzer.backend.inflation.domain;

import com.stockmarketanalyzer.backend.common.AuditableEntity;
import com.stockmarketanalyzer.backend.common.Units;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Annual inflation for one currency, stored as a percentage: {@code 4.6} means 4.6%, and a
 * negative value is deflation. Not a fraction -- the number is the one published in the news,
 * so what goes in is what you read.
 *
 * <p>Shared reference data, deliberately without an owner column: unlike a wealth entry or a
 * personal exchange-rate mark, "HUF inflation in 2025" is the same fact for every user. That
 * also puts it outside {@code /api/v1/wealth/**}, so writes need the ADMIN role like the rest
 * of the reference data.
 */
@Entity
@Table(
        name = "inflation_rate",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_inflation_rate_unit_year",
                        columnNames = {"unit", "year"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class InflationRate extends AuditableEntity {

    /** Currency the figure applies to, normalised upper-case: HUF, USD, EUR, ... */
    @Column(name = "unit", nullable = false, length = 16)
    @Setter(AccessLevel.NONE) // hand-written below so the unit is always normalised
    @ToString.Include
    private String unit;

    @Column(name = "year", nullable = false)
    @ToString.Include
    private Integer year;

    /** Percentage points, e.g. 4.6 for 4.6%. Negative values are deflation. */
    @Column(name = "rate", nullable = false, precision = 9, scale = 4)
    @ToString.Include
    private BigDecimal rate;

    public InflationRate(String unit, Integer year, BigDecimal rate) {
        this.unit = Units.normalise(unit);
        this.year = year;
        this.rate = rate;
    }

    public void setUnit(String unit) {
        this.unit = Units.normalise(unit);
    }
}
