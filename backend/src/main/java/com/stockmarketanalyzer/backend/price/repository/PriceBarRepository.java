package com.stockmarketanalyzer.backend.price.repository;

import com.stockmarketanalyzer.backend.instrument.domain.Instrument;
import com.stockmarketanalyzer.backend.price.domain.PriceBar;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PriceBarRepository extends JpaRepository<PriceBar, Long> {

    Page<PriceBar> findByInstrumentAndTradeDateBetween(
            Instrument instrument, LocalDate from, LocalDate to, Pageable pageable);

    List<PriceBar> findByInstrumentAndTradeDateBetweenOrderByTradeDateAsc(
            Instrument instrument, LocalDate from, LocalDate to);

    Optional<PriceBar> findFirstByInstrumentOrderByTradeDateDesc(Instrument instrument);

    Optional<PriceBar> findByInstrumentAndTradeDate(Instrument instrument, LocalDate tradeDate);

    long deleteByInstrumentAndTradeDateBetween(Instrument instrument, LocalDate from, LocalDate to);

    @Query(
            """
            select new com.stockmarketanalyzer.backend.price.repository.PriceStatistics(
                count(p), min(p.low), max(p.high), avg(p.close), min(p.tradeDate), max(p.tradeDate))
            from PriceBar p
            where p.instrument = :instrument
              and p.tradeDate between :from and :to
            """)
    PriceStatistics statistics(
            @Param("instrument") Instrument instrument,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
