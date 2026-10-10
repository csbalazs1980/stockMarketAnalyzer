package com.stockmarketanalyzer.backend.wealth.repository;

import com.stockmarketanalyzer.backend.common.Units;
import com.stockmarketanalyzer.backend.wealth.domain.ExchangeRate;
import com.stockmarketanalyzer.backend.wealth.domain.WealthEntry;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

/**
 * Dynamic filtering for the wealth endpoints. The owner predicate is not optional -- it is
 * added first on every specification so no query can accidentally span users.
 */
public final class WealthSpecifications {

    private WealthSpecifications() {}

    public static Specification<WealthEntry> entries(
            String ownerSub, LocalDate from, LocalDate to, String unit, Long parentId, boolean rootsOnly,
            String search) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("ownerSub"), ownerSub));

            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("entryDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("entryDate"), to));
            }
            if (StringUtils.hasText(unit)) {
                predicates.add(cb.equal(root.get("unit"), Units.normalise(unit)));
            }
            if (parentId != null) {
                predicates.add(cb.equal(root.get("parent").get("id"), parentId));
            }
            if (rootsOnly) {
                predicates.add(cb.isNull(root.get("parent")));
            }
            if (StringUtils.hasText(search)) {
                predicates.add(cb.like(cb.lower(root.get("name")), "%" + search.trim().toLowerCase() + "%"));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * Rates visible to one caller: their own plus the global ones. The visibility predicate is the
     * mandatory one, exactly as the owner predicate is for entries -- {@code global} only narrows it.
     *
     * @param global {@code null} for both kinds, {@code true} for global rows only, {@code false}
     *     for the caller's own only
     */
    public static Specification<ExchangeRate> rates(
            String ownerSub,
            Boolean global,
            String sourceUnit,
            String destinationUnit,
            LocalDate from,
            LocalDate to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (global == null) {
                predicates.add(cb.or(cb.isNull(root.get("ownerSub")), cb.equal(root.get("ownerSub"), ownerSub)));
            } else if (global) {
                predicates.add(cb.isNull(root.get("ownerSub")));
            } else {
                predicates.add(cb.equal(root.get("ownerSub"), ownerSub));
            }

            if (StringUtils.hasText(sourceUnit)) {
                predicates.add(cb.equal(root.get("sourceUnit"), Units.normalise(sourceUnit)));
            }
            if (StringUtils.hasText(destinationUnit)) {
                predicates.add(cb.equal(root.get("destinationUnit"), Units.normalise(destinationUnit)));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("rateDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("rateDate"), to));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
