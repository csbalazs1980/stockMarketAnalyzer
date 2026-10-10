package com.stockmarketanalyzer.backend.expense.service;

import com.stockmarketanalyzer.backend.common.ResourceConflictException;
import com.stockmarketanalyzer.backend.common.ResourceNotFoundException;
import com.stockmarketanalyzer.backend.common.csv.CsvFile;
import com.stockmarketanalyzer.backend.common.csv.CsvImportResult;
import com.stockmarketanalyzer.backend.common.csv.CsvRow;
import com.stockmarketanalyzer.backend.common.csv.CsvRowException;
import com.stockmarketanalyzer.backend.common.csv.CsvWriter;
import com.stockmarketanalyzer.backend.expense.domain.ExpenseCategory;
import com.stockmarketanalyzer.backend.expense.domain.ExpenseItem;
import com.stockmarketanalyzer.backend.expense.repository.ExpenseCategoryRepository;
import com.stockmarketanalyzer.backend.expense.repository.ExpenseItemRepository;
import com.stockmarketanalyzer.backend.expense.repository.ExpenseSpecifications;
import com.stockmarketanalyzer.backend.expense.web.dto.ExpenseCategoryRequest;
import com.stockmarketanalyzer.backend.security.CurrentUser;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * The tags expense items carry. Two kinds in one table, told apart by whether {@code ownerSub} is
 * null: a personal tag anybody may create for themselves, and a global tag only an administrator
 * may create, which everybody then sees.
 *
 * <p><strong>Why the role check is here and not in the security configuration.</strong>
 * {@code /api/v1/expenses/**} has to be {@code authenticated()} for every method, because an
 * ordinary {@code USER} writes their own rows -- the same exception the wealth and dividend slices
 * already carry. So "global rows are administrators only" cannot be a path rule; it is a property
 * of the payload, and this is the only place that can see it.
 *
 * <p>Names are compared case-insensitively throughout, so one person cannot end up with both
 * "Food" and "food". The database holds a unique index over {@code (owner_sub, lower(category))}
 * as the backstop, so two simultaneous creates cannot both win.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExpenseCategoryService {

    /** The import's single column. One name per line is all a tag list is. */
    public static final List<String> CSV_COLUMNS = List.of("category");

    /** The downloadable sample: the header, and three rows showing what it looks like. */
    public static final String CSV_SAMPLE =
            """
            category
            Subscription
            Insurance
            Household
            """;

    private final ExpenseCategoryRepository repository;
    private final ExpenseItemRepository items;
    private final CurrentUser currentUser;

    public Page<ExpenseCategory> search(String search, Boolean global, Pageable pageable) {
        return repository.findAll(
                ExpenseSpecifications.categories(currentUser.requireSubject(), search, global), pageable);
    }

    /**
     * The caller's own tags, one per line, in the shape the import reads.
     *
     * <p><strong>Global categories are left out</strong>, although the screen shows them alongside
     * the caller's own. They are not the caller's data -- they belong to everybody, and only an
     * administrator can create one -- so importing this file into another account would turn each
     * of them into a personal duplicate of a tag that account can already see.
     */
    public String exportCsv() {
        List<ExpenseCategory> own = repository.findAll(
                ExpenseSpecifications.categories(currentUser.requireSubject(), null, false), Sort.by("category"));
        CsvWriter csv = new CsvWriter(CSV_COLUMNS);
        for (ExpenseCategory category : own) {
            csv.row(category.getCategory());
        }
        log.debug("Exported {} expense category rows", own.size());
        return csv.toCsv();
    }

    /** One category the caller may use. Somebody else's is a 404, never a 403. */
    public ExpenseCategory get(Long id) {
        return repository
                .findVisible(id, currentUser.requireSubject())
                .orElseThrow(() -> new ResourceNotFoundException("No expense category found with id %d".formatted(id)));
    }

    @Transactional
    public ExpenseCategory create(ExpenseCategoryRequest request) {
        String ownerSub = currentUser.requireSubject();
        String name = normalise(request.category());
        boolean global = request.isGlobal();
        if (global) {
            requireAdmin("create a global expense category");
        }
        rejectDuplicate(name, global, ownerSub);

        ExpenseCategory saved = repository.save(new ExpenseCategory(global ? null : ownerSub, name));
        log.debug("Created {} expense category '{}' ({})", global ? "global" : "personal", name, saved.getId());
        return saved;
    }

    /**
     * Renames a category. What it deliberately cannot do is move a row between the two kinds: a
     * personal tag turned global would hand one person's label to everybody, and a global tag
     * turned personal would silently strip it from every other user's items. Both are
     * delete-and-recreate decisions, not edits, so they are refused with an explanation rather
     * than performed quietly.
     */
    @Transactional
    public ExpenseCategory update(Long id, ExpenseCategoryRequest request) {
        ExpenseCategory category = requireWritable(id);
        if (request.isGlobal() != category.isGlobal()) {
            throw new IllegalArgumentException(
                    "A category cannot be changed between personal and global. Create the other one and delete this.");
        }

        String name = normalise(request.category());
        if (!name.equalsIgnoreCase(category.getCategory())) {
            rejectDuplicate(name, category.isGlobal(), currentUser.requireSubject());
        }
        category.setCategory(name);
        return repository.save(category);
    }

    /**
     * Deletes a category, and refuses by default to do it silently out from under the items using
     * it: a tag in use is a {@code 409} unless {@code force} is set, exactly as deleting a wealth
     * entry with children is. With {@code force}, the links go and the items stay -- a tag is a
     * label, so losing one must never lose the expense it was on.
     */
    @Transactional
    public void delete(Long id, boolean force) {
        ExpenseCategory category = requireWritable(id);
        long usages = items.countByCategories_Id(id);
        if (usages > 0 && !force) {
            throw new ResourceConflictException(
                    "'%s' is used by %d expense item(s). Delete it with force=true to untag them."
                            .formatted(category.getCategory(), usages));
        }
        if (usages > 0) {
            // The owning side of the ManyToMany is the item, so the link rows only go when each
            // item's collection is updated. Deleting the category first would hit the FK instead.
            for (ExpenseItem item : items.findByCategories_Id(id)) {
                item.getCategories().removeIf(tag -> tag.getId().equals(id));
                items.save(item);
            }
            items.flush();
        }
        repository.delete(category);
        log.info("Deleted expense category {} ('{}'), untagging {} item(s)", id, category.getCategory(), usages);
    }

    /**
     * Stores a whole file of tag names at once.
     *
     * <p>One decision is worth spelling out: <strong>whether the file is global is asked once, in
     * the dialog, rather than per row.</strong> A {@code global} column would have to answer what a
     * non-administrator's {@code true} means, and either reading is bad -- failing those rows
     * punishes them for a column they were never meant to fill in, and honouring it is a privilege
     * escalation. One checkbox, on a screen that only shows it to administrators, has neither
     * problem. An ordinary user's import is personal by construction.
     *
     * <p>A name that already exists -- as the caller's own tag <em>or</em> as a global one -- is
     * counted as a duplicate and skipped, which is what makes re-uploading an overlapping list
     * safe. That is also the "check if it already exists" the single-row path does, reported as a
     * count instead of a 409, because one repeated name should not cost a file of forty.
     */
    @Transactional
    public CsvImportResult importCsv(MultipartFile file, boolean global) {
        String ownerSub = currentUser.requireSubject();
        if (global) {
            requireAdmin("import global expense categories");
        }
        CsvFile csv = CsvFile.read(file, CSV_COLUMNS);
        Set<String> written = new LinkedHashSet<>();
        int processed = 0;
        int failed = 0;
        int duplicates = 0;

        for (CsvRow row : csv.rows()) {
            try {
                String name = normalise(row.requireText("category"));
                if (!written.add(name.toLowerCase(Locale.ROOT)) || exists(name, global, ownerSub)) {
                    duplicates++;
                    continue;
                }
                repository.save(new ExpenseCategory(global ? null : ownerSub, name));
                processed++;
            } catch (CsvRowException ex) {
                failed++;
                log.debug("Skipped expense category CSV row: {}", ex.getMessage());
            }
        }

        log.info(
                "Imported {} of {} expense category rows ({} duplicates, global={})",
                processed,
                csv.rows().size(),
                duplicates,
                global);
        return new CsvImportResult(csv.rows().size(), processed, failed, duplicates);
    }

    /**
     * Resolves the ids an item wants to carry, in one query.
     *
     * <p>The count is compared rather than each id being looked up: "no such category" and
     * "somebody else's category" have to be the same answer, or a 404 per id becomes a way of
     * discovering what other people have tagged their expenses with.
     */
    public Set<ExpenseCategory> resolveVisible(Set<Long> ids, String ownerSub) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        List<ExpenseCategory> found = repository.findAllVisible(ids, ownerSub);
        if (found.size() != ids.size()) {
            throw new ResourceNotFoundException(
                    "One or more of the categories %s does not exist or is not yours".formatted(ids));
        }
        return new LinkedHashSet<>(found);
    }

    /**
     * What the item import does with a tag named in a file: use the global one if there is one,
     * then the caller's own, and only then create a personal tag. Preferring the global match is
     * what stops a file that says "Insurance" from giving everybody a personal copy of a tag the
     * installation already shares.
     */
    @Transactional
    public ExpenseCategory resolveOrCreateByName(String rawName, String ownerSub) {
        String name = normalise(rawName);
        Optional<ExpenseCategory> existing = repository
                .findFirstByOwnerSubIsNullAndCategoryIgnoreCase(name)
                .or(() -> repository.findFirstByOwnerSubAndCategoryIgnoreCase(ownerSub, name));
        return existing.orElseGet(() -> repository.save(new ExpenseCategory(ownerSub, name)));
    }

    /** The caller's own row, or -- for an administrator -- a global one. */
    private ExpenseCategory requireWritable(Long id) {
        String ownerSub = currentUser.requireSubject();
        Optional<ExpenseCategory> own = repository.findByIdAndOwnerSub(id, ownerSub);
        if (own.isPresent()) {
            return own.get();
        }
        ExpenseCategory global = repository
                .findByIdAndOwnerSubIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("No expense category found with id %d".formatted(id)));
        requireAdmin("change a global expense category");
        return global;
    }

    /**
     * A personal tag is refused when a <em>global</em> one of that name already exists, not only
     * when the caller has their own: two identical labels in one picker, one of them shared and one
     * not, is a bug report waiting to happen.
     */
    private void rejectDuplicate(String name, boolean global, String ownerSub) {
        boolean globalExists = repository.existsByOwnerSubIsNullAndCategoryIgnoreCase(name);
        if (global) {
            if (globalExists) {
                throw new ResourceConflictException(
                        "'%s' already exists as a global category.".formatted(name));
            }
            return;
        }
        if (globalExists) {
            throw new ResourceConflictException(
                    "'%s' already exists as a global category, so there is no need for your own."
                            .formatted(name));
        }
        if (repository.existsByOwnerSubAndCategoryIgnoreCase(ownerSub, name)) {
            throw new ResourceConflictException("You already have a category called '%s'.".formatted(name));
        }
    }

    private boolean exists(String name, boolean global, String ownerSub) {
        return repository.existsByOwnerSubIsNullAndCategoryIgnoreCase(name)
                || (!global && repository.existsByOwnerSubAndCategoryIgnoreCase(ownerSub, name));
    }

    /**
     * Kept as typed -- a tag is a label somebody reads, and upper-casing it the way {@code Units}
     * upper-cases a currency would turn the table into shouting. Only the surrounding whitespace
     * goes; uniqueness is handled case-insensitively instead.
     */
    private static String normalise(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (!StringUtils.hasText(trimmed)) {
            throw new IllegalArgumentException("A category name cannot be blank");
        }
        return trimmed;
    }

    private void requireAdmin(String action) {
        if (!currentUser.isAdmin()) {
            throw new AccessDeniedException("Only an administrator may %s.".formatted(action));
        }
    }
}
