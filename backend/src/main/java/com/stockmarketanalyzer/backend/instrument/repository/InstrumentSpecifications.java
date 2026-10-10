package com.stockmarketanalyzer.backend.instrument.repository;

import com.stockmarketanalyzer.backend.instrument.domain.AssetType;
import com.stockmarketanalyzer.backend.instrument.domain.Instrument;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

/**
 * Dynamic filtering for {@code GET /api/v1/instruments}. Every criterion is optional;
 * null / blank values simply drop out of the generated WHERE clause.
 */
public final class InstrumentSpecifications {

    private InstrumentSpecifications() {}

    public static Specification<Instrument> filter(
            String search, AssetType assetType, String exchange, Boolean active) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.hasText(search)) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("symbol")), pattern),
                        cb.like(cb.lower(root.get("name")), pattern)));
            }
            if (assetType != null) {
                predicates.add(cb.equal(root.get("assetType"), assetType));
            }
            if (StringUtils.hasText(exchange)) {
                predicates.add(cb.equal(cb.lower(root.get("exchange")), exchange.trim().toLowerCase()));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
