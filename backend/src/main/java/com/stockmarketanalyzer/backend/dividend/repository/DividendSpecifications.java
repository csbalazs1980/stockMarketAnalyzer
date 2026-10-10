package com.stockmarketanalyzer.backend.dividend.repository;

import com.stockmarketanalyzer.backend.common.Units;
import com.stockmarketanalyzer.backend.dividend.domain.Dividend;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

/**
 * Dynamic filtering for the dividend endpoints. As in {@code WealthSpecifications}, the owner
 * predicate is not optional -- it is added first every time, so no query can span users.
 *
 * <p>There is deliberately no free-text search: {@code instrumentId} is the exact version of
 * the same filter, and the UI's instrument picker already types ahead over ticker and name.
 */
public final class DividendSpecifications {

    private DividendSpecifications() {}

    public static Specification<Dividend> dividends(
            String ownerSub, LocalDate from, LocalDate to, Long instrumentId, String unit) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("ownerSub"), ownerSub));

            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("paymentDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("paymentDate"), to));
            }
            if (instrumentId != null) {
                predicates.add(cb.equal(root.get("instrument").get("id"), instrumentId));
            }
            if (StringUtils.hasText(unit)) {
                predicates.add(cb.equal(root.get("unit"), Units.normalise(unit)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
