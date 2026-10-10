package com.stockmarketanalyzer.backend.expense.repository;

import com.stockmarketanalyzer.backend.common.Units;
import com.stockmarketanalyzer.backend.expense.domain.ExpenseCategory;
import com.stockmarketanalyzer.backend.expense.domain.ExpenseItem;
import com.stockmarketanalyzer.backend.expense.domain.RecurrencePeriod;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

/** Dynamic filtering for both expense endpoints. */
public final class ExpenseSpecifications {

    private ExpenseSpecifications() {}

    /**
     * As in {@code WealthSpecifications}, the owner predicate is not optional -- it is added first
     * every time, so no query can span users.
     *
     * @param activeOn when set, only rows being charged on that date: started on or before it and
     *     not expired by it. A row with no start date has always been charged, and one with no
     *     expiry still is -- the two nulls are open ends of the interval, not missing data.
     */
    public static Specification<ExpenseItem> items(
            String ownerSub,
            String search,
            String unit,
            RecurrencePeriod period,
            Long categoryId,
            LocalDate activeOn) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("ownerSub"), ownerSub));

            if (StringUtils.hasText(search)) {
                predicates.add(cb.like(
                        cb.lower(root.get("name")), "%" + search.toLowerCase(Locale.ROOT).trim() + "%"));
            }
            if (StringUtils.hasText(unit)) {
                predicates.add(cb.equal(root.get("unit"), Units.normalise(unit)));
            }
            if (period != null) {
                predicates.add(cb.equal(root.get("recurrencePeriod"), period));
            }
            if (categoryId != null) {
                // An inner join on one category id can match at most one link row per item, so
                // this cannot duplicate rows and needs no distinct -- which would otherwise have
                // to be applied to the count query as well to keep the paging honest.
                predicates.add(cb.equal(root.join("categories", JoinType.INNER).get("id"), categoryId));
            }
            if (activeOn != null) {
                predicates.add(cb.or(
                        cb.isNull(root.get("startDate")), cb.lessThanOrEqualTo(root.get("startDate"), activeOn)));
                predicates.add(cb.or(
                        cb.isNull(root.get("expiryDate")),
                        cb.greaterThanOrEqualTo(root.get("expiryDate"), activeOn)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * Categories visible to one caller: their own plus the global ones. The visibility predicate
     * is the mandatory one here, exactly as the owner predicate is above -- {@code global} only
     * narrows it further.
     *
     * @param global {@code null} for both kinds, {@code true} for global rows only, {@code false}
     *     for the caller's own only
     */
    public static Specification<ExpenseCategory> categories(String ownerSub, String search, Boolean global) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (global == null) {
                predicates.add(cb.or(cb.isNull(root.get("ownerSub")), cb.equal(root.get("ownerSub"), ownerSub)));
            } else if (global) {
                predicates.add(cb.isNull(root.get("ownerSub")));
            } else {
                predicates.add(cb.equal(root.get("ownerSub"), ownerSub));
            }

            if (StringUtils.hasText(search)) {
                predicates.add(cb.like(
                        cb.lower(root.get("category")),
                        "%" + search.toLowerCase(Locale.ROOT).trim() + "%"));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
