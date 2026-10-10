package com.stockmarketanalyzer.backend.expense.domain;

import com.stockmarketanalyzer.backend.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A tag an expense item can carry: "Subscription", "Insurance", "Rezsi".
 *
 * <p>{@code ownerSub} is <strong>nullable, and that is the whole design</strong>: a row with an
 * owner is that person's own tag, a row without one is global and offered to everybody. Only an
 * administrator may write a global row -- enforced in the service, because the path itself has to
 * stay open to an ordinary {@code USER} writing their own.
 *
 * <p>The name is stored as typed rather than upper-cased the way {@code Units} normalises a
 * currency: a tag is a label a person reads, and "SUBSCRIPTION" in a table is shouting. Matching
 * is case-insensitive instead -- in the service for the duplicate check, and in the database
 * through a unique index over {@code lower(category)}, so "Food" and "food" cannot both exist
 * for one owner.
 */
@Entity
@Table(name = "expense_category")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class ExpenseCategory extends AuditableEntity {

    /**
     * Keycloak {@code sub} of the owner, or {@code null} for a global category. Never taken from
     * the request body; the request carries a {@code global} flag and the service decides.
     */
    @Column(name = "owner_sub", length = 64)
    @Setter(AccessLevel.NONE)
    private String ownerSub;

    @Column(name = "category", nullable = false, length = 64)
    @ToString.Include
    private String category;

    public ExpenseCategory(String ownerSub, String category) {
        this.ownerSub = ownerSub;
        this.category = category;
    }

    /** True when this tag belongs to the installation rather than to one person. */
    public boolean isGlobal() {
        return ownerSub == null;
    }
}
