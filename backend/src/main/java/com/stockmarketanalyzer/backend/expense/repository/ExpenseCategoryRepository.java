package com.stockmarketanalyzer.backend.expense.repository;

import com.stockmarketanalyzer.backend.expense.domain.ExpenseCategory;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Categories are the one table here that is <em>not</em> purely owner-scoped: a row whose
 * {@code ownerSub} is null is global and belongs to everybody. "Visible to me" therefore means
 * mine <em>or</em> global, and that is what every read below says -- a finder that only matched
 * the owner would hide the shared tags, and one that matched nothing would expose other people's.
 */
public interface ExpenseCategoryRepository
        extends JpaRepository<ExpenseCategory, Long>, JpaSpecificationExecutor<ExpenseCategory> {

    /** One category the caller may use: their own, or a global one. Anything else is a miss. */
    @Query("select c from ExpenseCategory c where c.id = :id and (c.ownerSub is null or c.ownerSub = :ownerSub)")
    Optional<ExpenseCategory> findVisible(@Param("id") Long id, @Param("ownerSub") String ownerSub);

    /**
     * The visible categories among {@code ids}, in one query. The caller compares the size of the
     * result with the size of the request rather than looking each id up: a missing id and an id
     * belonging to somebody else have to be the same answer, or the 404 becomes a way to find out
     * which ids exist.
     */
    @Query("select c from ExpenseCategory c where c.id in :ids and (c.ownerSub is null or c.ownerSub = :ownerSub)")
    List<ExpenseCategory> findAllVisible(@Param("ids") Collection<Long> ids, @Param("ownerSub") String ownerSub);

    /** Editing and deleting only ever touch the caller's own rows. */
    Optional<ExpenseCategory> findByIdAndOwnerSub(Long id, String ownerSub);

    /** A global row, for the administrator paths. */
    Optional<ExpenseCategory> findByIdAndOwnerSubIsNull(Long id);

    /**
     * The duplicate check, case-insensitively: "Food" and "food" are one tag. Backed by a unique
     * index over {@code lower(category)}, so a race that slips past this still cannot store two.
     */
    boolean existsByOwnerSubAndCategoryIgnoreCase(String ownerSub, String category);

    boolean existsByOwnerSubIsNullAndCategoryIgnoreCase(String category);

    /**
     * What the CSV import resolves a name to. A global tag wins over creating a personal
     * duplicate of it, so this is tried first.
     */
    Optional<ExpenseCategory> findFirstByOwnerSubIsNullAndCategoryIgnoreCase(String category);

    Optional<ExpenseCategory> findFirstByOwnerSubAndCategoryIgnoreCase(String ownerSub, String category);
}
