package com.stockmarketanalyzer.backend.instrument.repository;

import com.stockmarketanalyzer.backend.instrument.domain.Instrument;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface InstrumentRepository extends JpaRepository<Instrument, Long>, JpaSpecificationExecutor<Instrument> {

    Optional<Instrument> findBySymbol(String symbol);

    boolean existsBySymbol(String symbol);

    /** Every instrument, projected down to picker fields. Unpaged on purpose -- see the controller. */
    List<InstrumentOptionView> findAllByOrderBySymbolAsc();

    /** As above, restricted to active (or inactive) instruments. */
    List<InstrumentOptionView> findAllByActiveOrderBySymbolAsc(boolean active);
}
