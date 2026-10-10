package com.stockmarketanalyzer.backend.wealth.domain;

import com.stockmarketanalyzer.backend.common.AuditableEntity;
import com.stockmarketanalyzer.backend.common.Units;
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
 * One line of a wealth snapshot: {@code quantity} of {@code unit} held on {@code entryDate}.
 *
 * <p>Nothing about the entry's worth is stored. The value is derived on read from the
 * {@link ExchangeRate} rows the owner has supplied, so a corrected rate re-values the whole
 * history instead of leaving stale numbers behind.
 *
 * <p>{@code parent} lets rows roll up: the four savings accounts hang off a "savings" node,
 * which in turn hangs off the portfolio root. As with {@code PriceBar#instrument}, the lazy
 * association is kept out of {@code toString()}.
 */
@Entity
@Table(name = "wealth_entry")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class WealthEntry extends AuditableEntity {

    /** Keycloak {@code sub} of the owner. Never taken from the request body. */
    @Column(name = "owner_sub", nullable = false, length = 64)
    @Setter(AccessLevel.NONE)
    private String ownerSub;

    /** Human label for the row: {@code rev}, {@code tbsz24}, {@code junk silver}, ... */
    @Column(name = "name", nullable = false, length = 128)
    @ToString.Include
    private String name;

    /** Free-form unit of measure, normalised to upper case: USD, HUF, OZ, KG, ... */
    @Column(name = "unit", nullable = false, length = 16)
    @Setter(AccessLevel.NONE) // hand-written below so the unit is always normalised
    @ToString.Include
    private String unit;

    /**
     * How much of {@code unit} is held, or {@code null} for an aggregate row that only
     * exists to hold children. Null is not zero: a roll-up node holds nothing of its own,
     * whereas zero is a deliberate "I hold none of this".
     */
    @Column(name = "quantity", precision = 24, scale = 6)
    @ToString.Include
    private BigDecimal quantity;

    @Column(name = "entry_date", nullable = false)
    @ToString.Include
    private LocalDate entryDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id", foreignKey = @ForeignKey(name = "fk_wealth_entry_parent"))
    private WealthEntry parent;

    public WealthEntry(String ownerSub, String name, String unit, BigDecimal quantity, LocalDate entryDate) {
        this.ownerSub = ownerSub;
        this.name = name;
        this.unit = Units.normalise(unit);
        this.quantity = quantity;
        this.entryDate = entryDate;
    }

    public void setUnit(String unit) {
        this.unit = Units.normalise(unit);
    }
}
