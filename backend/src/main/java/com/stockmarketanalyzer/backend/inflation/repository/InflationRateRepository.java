package com.stockmarketanalyzer.backend.inflation.repository;

import com.stockmarketanalyzer.backend.inflation.domain.InflationRate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InflationRateRepository
        extends JpaRepository<InflationRate, Long>, JpaSpecificationExecutor<InflationRate> {

    Optional<InflationRate> findByUnitAndYear(String unit, Integer year);

    /** The newest year stored for a currency, or empty when it has none. */
    @Query("select max(r.year) from InflationRate r where r.unit = :unit")
    Optional<Integer> findLatestYear(@Param("unit") String unit);

    /** Every currency that has at least one figure, alphabetically -- the admin screen's filter. */
    @Query("select distinct r.unit from InflationRate r order by r.unit")
    List<String> findDistinctUnits();
}
