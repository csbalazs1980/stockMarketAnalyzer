package com.stockmarketanalyzer.backend.inflation.repository;

import com.stockmarketanalyzer.backend.common.Units;
import com.stockmarketanalyzer.backend.inflation.domain.InflationRate;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

/**
 * Dynamic filtering for {@code GET /api/v1/inflation-rates}. Every criterion is optional.
 */
public final class InflationRateSpecifications {

    private InflationRateSpecifications() {}

    public static Specification<InflationRate> filter(String unit, Integer fromYear, Integer toYear) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.hasText(unit)) {
                predicates.add(cb.equal(root.get("unit"), Units.normalise(unit)));
            }
            if (fromYear != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("year"), fromYear));
            }
            if (toYear != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("year"), toYear));
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
