package com.stockmarketanalyzer.backend.expense.domain;

import com.stockmarketanalyzer.backend.common.AuditableEntity;
import com.stockmarketanalyzer.backend.common.Units;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A cost that comes back: {@code amount} of {@code unit}, every {@code recurrenceInterval}
 * {@code recurrencePeriod}s, from {@code startDate} until {@code expiryDate}.
 *
 * <p>The recurrence is stored as an interval plus a period rather than as a preset, for the
 * reason spelled out on {@link RecurrencePeriod}: "quarterly" and "every 3 months" must not be
 * two different rows that mean the same thing. The form offers the presets; the table stores the
 * pair they map onto.
 *
 * <p><strong>Nothing normalised is stored.</strong> There is no monthly-equivalent column and no
 * converted value, for the same reason {@code WealthEntry} stores no {@code currentValue}: a
 * derived number in a row is a number that goes stale. What an expense costs per month is worked
 * out when somebody asks, from what was typed.
 *
 * <p>Both dates are optional, and they mean different things when absent. No {@code startDate} is
 * "this is simply what I pay, don't ask me since when" -- a rate with no calendar. No
 * {@code expiryDate} is the ordinary case: it has not got an end. A loan has both.
 *
 * <p>Categories are tags, so the association is a {@code ManyToMany} rather than a column: an
 * item can be both a Subscription and Entertainment, and a tag can be the owner's own or a global
 * one. As elsewhere here, the lazy association is kept out of {@code toString()}; id and
 * equality come from {@link com.stockmarketanalyzer.backend.common.BaseEntity}.
 */
@Entity
@Table(name = "expense_item")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class ExpenseItem extends AuditableEntity {

    /** Keycloak {@code sub} of the owner. Never taken from the request body. */
    @Column(name = "owner_sub", nullable = false, length = 64)
    @Setter(AccessLevel.NONE)
    private String ownerSub;

    /** What the expense is called -- "Car loan", "Netflix", "Gázszámla". */
    @Column(name = "name", nullable = false, length = 128)
    @ToString.Include
    private String name;

    /** What one occurrence costs. Zero is allowed: a plan can legitimately be free for now. */
    @Column(name = "amount", nullable = false, precision = 24, scale = 6)
    @ToString.Include
    private BigDecimal amount;

    /** Free-form unit of measure, normalised to upper case by {@link Units}: HUF, EUR, USD. */
    @Column(name = "unit", nullable = false, length = 16)
    @Setter(AccessLevel.NONE) // hand-written below so the unit is always normalised
    @ToString.Include
    private String unit;

    /** How many periods between occurrences. 1 is the common case; 2 + WEEKLY is fortnightly. */
    @Column(name = "recurrence_interval", nullable = false)
    @ToString.Include
    private Integer recurrenceInterval;

    @Enumerated(EnumType.STRING)
    @Column(name = "recurrence_period", nullable = false, length = 16)
    @ToString.Include
    private RecurrencePeriod recurrencePeriod;

    /** When the charges began, if the owner cared to say. */
    @Column(name = "start_date")
    private LocalDate startDate;

    /** The last date it is charged, or {@code null} for "no end in sight". */
    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    /**
     * {@code CascadeType} is deliberately neither {@code ALL} nor {@code REMOVE}: this owns the
     * rows in the join table, not the categories themselves. Deleting an item must never take a
     * tag -- let alone a global tag -- with it.
     */
    @ManyToMany(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "expense_item_category",
            joinColumns = @JoinColumn(
                    name = "expense_item_id",
                    foreignKey = @ForeignKey(name = "fk_expense_item_category_item")),
            inverseJoinColumns = @JoinColumn(
                    name = "expense_category_id",
                    foreignKey = @ForeignKey(name = "fk_expense_item_category_category")))
    @OrderBy("category asc")
    private Set<ExpenseCategory> categories = new LinkedHashSet<>();

    public ExpenseItem(
            String ownerSub,
            String name,
            BigDecimal amount,
            String unit,
            Integer recurrenceInterval,
            RecurrencePeriod recurrencePeriod,
            LocalDate startDate,
            LocalDate expiryDate) {
        this.ownerSub = ownerSub;
        this.name = name;
        this.amount = amount;
        this.unit = Units.normalise(unit);
        this.recurrenceInterval = recurrenceInterval;
        this.recurrencePeriod = recurrencePeriod;
        this.startDate = startDate;
        this.expiryDate = expiryDate;
    }

    public void setUnit(String unit) {
        this.unit = Units.normalise(unit);
    }

    /**
     * Replaces the whole tag set rather than patching it. The screen shows checkboxes and the
     * answer to "which categories?" is always the complete list, so a delta computed anywhere
     * else would be wrong the moment two tabs had the dialog open -- the same reasoning as the
     * admin screens' "set roles" call.
     */
    public void replaceCategories(Set<ExpenseCategory> replacement) {
        categories.clear();
        categories.addAll(replacement);
    }
}
