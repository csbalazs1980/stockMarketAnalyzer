package com.stockmarketanalyzer.backend.expense.repository;

import com.stockmarketanalyzer.backend.expense.domain.ExpenseItem;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Ownership is enforced here rather than in the controller, as in the wealth and dividend slices:
 * every finder takes the owner, so another subject's row is a 404 rather than a 403.
 *
 * <p>The paged search deliberately does <strong>not</strong> carry an entity graph for
 * {@code categories}. Fetching a to-many collection alongside {@code firstResult}/{@code maxResults}
 * makes Hibernate page <em>in memory</em> -- it reads every matching row, then throws most of them
 * away -- which is silent, gets slower as the table grows, and is what the HHH90003004 warning is
 * about. {@link #findWithCategoriesByIdIn} hydrates the tags of one page in a second query
 * instead: two queries, whatever the page size, and no N+1 either.
 */
public interface ExpenseItemRepository extends JpaRepository<ExpenseItem, Long>, JpaSpecificationExecutor<ExpenseItem> {

    @EntityGraph(attributePaths = "categories")
    Optional<ExpenseItem> findByIdAndOwnerSub(Long id, String ownerSub);

    /**
     * The page's rows with their tags attached. Order is not specified: the caller already knows
     * the order it asked for and re-imposes it, because {@code in (...)} does not promise one.
     */
    @EntityGraph(attributePaths = "categories")
    List<ExpenseItem> findWithCategoriesByIdIn(Collection<Long> ids);

    /**
     * The import's duplicate check. An expense item has no natural key, but unlike a dividend --
     * which can legitimately be paid twice on the same day for the same amount -- its
     * <em>name</em> is its identity in a household budget: there is one "Netflix". So re-uploading
     * an overlapping file skips what is already there rather than doubling it.
     */
    boolean existsByOwnerSubAndNameIgnoreCase(String ownerSub, String name);

    /** How many items carry a tag. Answers "is this category in use?" before it is deleted. */
    long countByCategories_Id(Long categoryId);

    /** The items carrying a tag, so a forced delete can detach it from each of them. */
    @EntityGraph(attributePaths = "categories")
    List<ExpenseItem> findByCategories_Id(Long categoryId);
}
