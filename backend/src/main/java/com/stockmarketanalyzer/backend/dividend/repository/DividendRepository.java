package com.stockmarketanalyzer.backend.dividend.repository;

import com.stockmarketanalyzer.backend.dividend.domain.Dividend;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Reads join the instrument in eagerly. {@code open-in-view} is off, so a controller mapping a
 * detached dividend would otherwise hit an uninitialised proxy the moment it reads the symbol
 * -- and fetching it also keeps a page of rows down to a single query.
 */
public interface DividendRepository extends JpaRepository<Dividend, Long>, JpaSpecificationExecutor<Dividend> {

    @Override
    @EntityGraph(attributePaths = "instrument")
    Page<Dividend> findAll(Specification<Dividend> specification, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "instrument")
    List<Dividend> findAll(Specification<Dividend> specification);

    /** The same, ordered -- what the CSV export reads. Sorting cannot page, so the graph is safe. */
    @Override
    @EntityGraph(attributePaths = "instrument")
    List<Dividend> findAll(Specification<Dividend> specification, Sort sort);

    /**
     * Every lookup goes through the owner as well as the id: a row belonging to somebody else
     * must be indistinguishable from a row that does not exist.
     */
    @EntityGraph(attributePaths = "instrument")
    Optional<Dividend> findByIdAndOwnerSub(Long id, String ownerSub);

    /** Guards the instrument delete: a paid dividend is typed-in history, not refetchable data. */
    boolean existsByInstrumentId(Long instrumentId);

    /**
     * A dividend has no natural key, so "the same row again" is defined as every field matching.
     * The CSV import uses this to skip rows it already holds, which is what makes re-uploading a
     * file safe rather than a way to double your history.
     *
     * <p>Postgres compares {@code numeric} by value, not by scale, so 42.75 matches a stored
     * 42.750000.
     */
    boolean existsByOwnerSubAndInstrumentIdAndAmountAndUnitAndPaymentDate(
            String ownerSub, Long instrumentId, BigDecimal amount, String unit, LocalDate paymentDate);
}
