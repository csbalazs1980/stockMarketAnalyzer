package com.stockmarketanalyzer.backend.wealth.repository;

import com.stockmarketanalyzer.backend.wealth.domain.ExchangeRate;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Personal rates are addressed through their owner, global ones through {@code OwnerSubIsNull}.
 * Spring Data cannot derive "owner = :sub" to also match NULL, so every lookup exists in both
 * spellings and the service decides which to ask, and in what order.
 */
public interface ExchangeRateRepository
        extends JpaRepository<ExchangeRate, Long>, JpaSpecificationExecutor<ExchangeRate> {

    Optional<ExchangeRate> findByIdAndOwnerSub(Long id, String ownerSub);

    Optional<ExchangeRate> findByIdAndOwnerSubIsNull(Long id);

    Optional<ExchangeRate> findByOwnerSubAndSourceUnitAndDestinationUnitAndRateDate(
            String ownerSub, String sourceUnit, String destinationUnit, LocalDate rateDate);

    Optional<ExchangeRate> findByOwnerSubIsNullAndSourceUnitAndDestinationUnitAndRateDate(
            String sourceUnit, String destinationUnit, LocalDate rateDate);

    /** The owner's rate in force on a date: the newest one they quoted on or before it. */
    Optional<ExchangeRate> findFirstByOwnerSubAndSourceUnitAndDestinationUnitAndRateDateLessThanEqualOrderByRateDateDesc(
            String ownerSub, String sourceUnit, String destinationUnit, LocalDate rateDate);

    /** The global rate in force on a date. */
    Optional<ExchangeRate> findFirstByOwnerSubIsNullAndSourceUnitAndDestinationUnitAndRateDateLessThanEqualOrderByRateDateDesc(
            String sourceUnit, String destinationUnit, LocalDate rateDate);

    /**
     * The newest quote the owner holds for every pair, one row each.
     *
     * <p>This is what "the most recent rate available" means where there is no date to be as-of.
     * A recurring expense carries no date of its own, so the screen that converts it needs the
     * latest quote for each pair and nothing else -- and asking for that here is one small query
     * rather than the browser paging through every rate ever stored to find the newest few.
     *
     * <p>The unique index on (owner, source, destination, date) is what keeps this to one row per
     * pair: there cannot be two quotes sharing the maximum date.
     */
    @Query(
            """
            select quote from ExchangeRate quote
            where quote.ownerSub = :ownerSub
              and quote.rateDate = (
                  select max(newest.rateDate) from ExchangeRate newest
                  where newest.ownerSub = quote.ownerSub
                    and newest.sourceUnit = quote.sourceUnit
                    and newest.destinationUnit = quote.destinationUnit)
            order by quote.sourceUnit asc, quote.destinationUnit asc
            """)
    List<ExchangeRate> findLatestPerPair(@Param("ownerSub") String ownerSub);

    /** The same over the global rates. A separate query because {@code = null} never matches. */
    @Query(
            """
            select quote from ExchangeRate quote
            where quote.ownerSub is null
              and quote.rateDate = (
                  select max(newest.rateDate) from ExchangeRate newest
                  where newest.ownerSub is null
                    and newest.sourceUnit = quote.sourceUnit
                    and newest.destinationUnit = quote.destinationUnit)
            order by quote.sourceUnit asc, quote.destinationUnit asc
            """)
    List<ExchangeRate> findLatestGlobalPerPair();
}
