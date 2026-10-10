package com.stockmarketanalyzer.backend.wealth.repository;

import com.stockmarketanalyzer.backend.wealth.domain.WealthEntry;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Reads join the parent in eagerly. {@code open-in-view} is off, so a controller mapping a
 * detached entry would otherwise hit an uninitialised proxy the moment it reads the parent id
 * -- and fetching it also keeps a page of rows down to a single query.
 */
public interface WealthEntryRepository
        extends JpaRepository<WealthEntry, Long>, JpaSpecificationExecutor<WealthEntry> {

    @Override
    @EntityGraph(attributePaths = "parent")
    Page<WealthEntry> findAll(Specification<WealthEntry> specification, Pageable pageable);

    /**
     * Every lookup goes through the owner as well as the id: a row belonging to somebody else
     * must be indistinguishable from a row that does not exist.
     */
    @EntityGraph(attributePaths = "parent")
    Optional<WealthEntry> findByIdAndOwnerSub(Long id, String ownerSub);

    @EntityGraph(attributePaths = "parent")
    List<WealthEntry> findByOwnerSubAndEntryDateOrderByIdAsc(String ownerSub, LocalDate entryDate);

    @EntityGraph(attributePaths = "parent")
    List<WealthEntry> findByOwnerSubAndParentIdOrderByIdAsc(String ownerSub, Long parentId);

    /**
     * Every row the owner has, parents included. Roll-ups need the whole forest: a page of
     * results cannot be summed against children that were filtered out of it. Ordered so that
     * a clone writes siblings in the order they were first recorded rather than whatever the
     * database happens to return.
     */
    @EntityGraph(attributePaths = "parent")
    List<WealthEntry> findByOwnerSubOrderByIdAsc(String ownerSub);

    boolean existsByParentId(Long parentId);

    /** Newest snapshot date the owner has recorded, or empty when they have none. */
    Optional<WealthEntry> findFirstByOwnerSubOrderByEntryDateDesc(String ownerSub);
}
