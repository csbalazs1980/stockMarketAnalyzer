package com.stockmarketanalyzer.backend.expense.service;

import com.stockmarketanalyzer.backend.common.ResourceNotFoundException;
import com.stockmarketanalyzer.backend.common.Units;
import com.stockmarketanalyzer.backend.common.csv.CsvFile;
import com.stockmarketanalyzer.backend.common.csv.CsvImportResult;
import com.stockmarketanalyzer.backend.common.csv.CsvRow;
import com.stockmarketanalyzer.backend.common.csv.CsvRowException;
import com.stockmarketanalyzer.backend.common.csv.CsvWriter;
import com.stockmarketanalyzer.backend.expense.domain.ExpenseCategory;
import com.stockmarketanalyzer.backend.expense.domain.ExpenseItem;
import com.stockmarketanalyzer.backend.expense.domain.RecurrencePeriod;
import com.stockmarketanalyzer.backend.expense.repository.ExpenseItemRepository;
import com.stockmarketanalyzer.backend.expense.repository.ExpenseSpecifications;
import com.stockmarketanalyzer.backend.expense.web.dto.ExpenseItemRequest;
import com.stockmarketanalyzer.backend.security.CurrentUser;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * CRUD over the caller's recurring expenses.
 *
 * <p>Nothing here computes what an expense costs per month. That is deliberate and it is the one
 * design decision worth knowing about this slice: normalising "every 2 weeks" into a monthly
 * figure needs an average month, and the only honest average is a presentation choice, so the
 * arithmetic lives where the period selector does -- in the browser. The API returns what was
 * typed. This also keeps the totals consistent with what is on screen: the same rows, the same
 * period, one calculation.
 *
 * <p>Amounts are likewise <em>not</em> converted between currencies here, unlike the wealth and
 * dividend slices. Those exist to answer "what is all of this worth together", which cannot be
 * done without rates; a list of bills is useful read per currency, and summing HUF bills with EUR
 * bills through a rate that moves daily would make a budget that changes when nothing did.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExpenseItemService {

    /**
     * Columns the file <em>must</em> carry. {@code interval}, {@code startDate}, {@code expiryDate}
     * and {@code categories} are read when present and defaulted when not, so the simplest useful
     * file is four columns wide.
     */
    public static final List<String> CSV_COLUMNS = List.of("name", "amount", "unit", "period");

    /** Every column the import understands, in the order the sample writes them. */
    public static final List<String> CSV_ALL_COLUMNS =
            List.of("name", "amount", "unit", "interval", "period", "startDate", "expiryDate", "categories");

    /**
     * The downloadable sample. Three rows on purpose: a plain monthly bill, a fortnightly one (to
     * show what the interval column is for), and a yearly one with an end date and two tags.
     */
    public static final String CSV_SAMPLE =
            """
            name,amount,unit,interval,period,startDate,expiryDate,categories
            Netflix,4490,HUF,1,MONTHLY,2024-01-01,,Subscription|Entertainment
            Cleaner,12000,HUF,2,WEEKLY,,,Household
            Car insurance,120000,HUF,1,YEARLY,2025-03-15,2031-03-15,Insurance|Car
            """;

    /** How categories are separated inside one cell. Not a comma: that is the field separator. */
    private static final String CATEGORY_SEPARATOR = "\\|";

    /**
     * Spellings accepted for the period column, beyond the enum's own names. A file filled in by
     * hand says "monthly" or "month", and refusing it would be pedantry; anything genuinely
     * ambiguous is still a failed row rather than a guess.
     */
    private static final Map<String, RecurrencePeriod> PERIOD_ALIASES = Map.ofEntries(
            Map.entry("day", RecurrencePeriod.DAILY),
            Map.entry("days", RecurrencePeriod.DAILY),
            Map.entry("daily", RecurrencePeriod.DAILY),
            Map.entry("week", RecurrencePeriod.WEEKLY),
            Map.entry("weeks", RecurrencePeriod.WEEKLY),
            Map.entry("weekly", RecurrencePeriod.WEEKLY),
            Map.entry("month", RecurrencePeriod.MONTHLY),
            Map.entry("months", RecurrencePeriod.MONTHLY),
            Map.entry("monthly", RecurrencePeriod.MONTHLY),
            Map.entry("year", RecurrencePeriod.YEARLY),
            Map.entry("years", RecurrencePeriod.YEARLY),
            Map.entry("yearly", RecurrencePeriod.YEARLY),
            Map.entry("annual", RecurrencePeriod.YEARLY),
            Map.entry("annually", RecurrencePeriod.YEARLY));

    private final ExpenseItemRepository repository;
    private final ExpenseCategoryService categories;
    private final CurrentUser currentUser;

    /**
     * A page of expenses with their tags attached.
     *
     * <p>Two queries rather than one joined fetch: a to-many entity graph plus {@code LIMIT} makes
     * Hibernate page in memory -- see {@link ExpenseItemRepository}. The second query is keyed on
     * the page's ids, and the page's own order is re-imposed afterwards because {@code in (...)}
     * does not promise one.
     */
    public Page<ExpenseItem> search(
            String search, String unit, RecurrencePeriod period, Long categoryId, LocalDate activeOn,
            Pageable pageable) {
        Page<ExpenseItem> page = repository.findAll(
                ExpenseSpecifications.items(
                        currentUser.requireSubject(), search, unit, period, categoryId, activeOn),
                pageable);
        if (page.isEmpty()) {
            return page;
        }
        return new PageImpl<>(withCategories(page.getContent()), pageable, page.getTotalElements());
    }

    /**
     * Every expense the caller has, tags included, in the shape the import reads.
     *
     * <p>Tags are written by name into the same '|'-separated cell the import splits, not by id:
     * an id means nothing in another account, and being able to move the file to one is half of
     * what an export is for. Names are sorted inside the cell, and rows by name, so two exports of
     * unchanged data are the same file.
     *
     * <p>The screen's filters are deliberately not applied -- "download my data" means all of it.
     */
    public String exportCsv() {
        List<ExpenseItem> items = repository.findAll(
                ExpenseSpecifications.items(currentUser.requireSubject(), null, null, null, null, null),
                Sort.by("name"));
        CsvWriter csv = new CsvWriter(CSV_ALL_COLUMNS);
        for (ExpenseItem item : withCategories(items)) {
            csv.row(
                    item.getName(),
                    item.getAmount(),
                    item.getUnit(),
                    item.getRecurrenceInterval(),
                    item.getRecurrencePeriod(),
                    item.getStartDate(),
                    item.getExpiryDate(),
                    item.getCategories().stream()
                            .map(ExpenseCategory::getCategory)
                            .sorted(String.CASE_INSENSITIVE_ORDER)
                            .collect(Collectors.joining("|")));
        }
        log.debug("Exported {} expense item rows", items.size());
        return csv.toCsv();
    }

    /**
     * The same rows with their tags attached, in the order they arrived.
     *
     * <p>Two queries rather than one join fetch, for the reason {@link ExpenseItemRepository}
     * gives, and the order is re-imposed afterwards because {@code in (...)} does not promise one.
     */
    private List<ExpenseItem> withCategories(List<ExpenseItem> items) {
        if (items.isEmpty()) {
            return items;
        }
        List<Long> ids = items.stream().map(ExpenseItem::getId).toList();
        Map<Long, ExpenseItem> hydrated = repository.findWithCategoriesByIdIn(ids).stream()
                .collect(Collectors.toMap(ExpenseItem::getId, Function.identity()));
        return ids.stream().map(hydrated::get).filter(item -> item != null).toList();
    }

    public ExpenseItem get(Long id) {
        return repository
                .findByIdAndOwnerSub(id, currentUser.requireSubject())
                .orElseThrow(() -> new ResourceNotFoundException("No expense item found with id %d".formatted(id)));
    }

    @Transactional
    public ExpenseItem create(ExpenseItemRequest request) {
        String ownerSub = currentUser.requireSubject();
        validateDates(request.startDate(), request.expiryDate());

        ExpenseItem item = new ExpenseItem(
                ownerSub,
                request.name().trim(),
                request.amount(),
                request.unit(),
                request.intervalOrOne(),
                request.recurrencePeriod(),
                request.startDate(),
                request.expiryDate());
        item.replaceCategories(categories.resolveVisible(request.categoryIdsOrEmpty(), ownerSub));

        ExpenseItem saved = repository.save(item);
        log.debug(
                "Created expense item {} ('{}': {} {} every {} {})",
                saved.getId(),
                saved.getName(),
                saved.getAmount(),
                saved.getUnit(),
                saved.getRecurrenceInterval(),
                saved.getRecurrencePeriod());
        return saved;
    }

    /** A PUT, so the tag set sent replaces the one stored rather than adding to it. */
    @Transactional
    public ExpenseItem update(Long id, ExpenseItemRequest request) {
        ExpenseItem item = get(id);
        validateDates(request.startDate(), request.expiryDate());

        item.setName(request.name().trim());
        item.setAmount(request.amount());
        item.setUnit(request.unit());
        item.setRecurrenceInterval(request.intervalOrOne());
        item.setRecurrencePeriod(request.recurrencePeriod());
        item.setStartDate(request.startDate());
        item.setExpiryDate(request.expiryDate());
        item.replaceCategories(categories.resolveVisible(request.categoryIdsOrEmpty(), item.getOwnerSub()));
        return repository.save(item);
    }

    @Transactional
    public void delete(Long id) {
        ExpenseItem item = get(id);
        repository.delete(item);
        log.info("Deleted expense item {} ('{}')", item.getId(), item.getName());
    }

    /**
     * Records a whole file of recurring costs at once.
     *
     * <p><strong>Tags named in the file are created if they do not exist</strong>, as personal
     * categories, after a global one of that name has been looked for. That is the opposite of what
     * the dividend import does with an unknown ticker, and for a good reason: an instrument is
     * reference data that the file cannot be trusted to invent, whereas a tag <em>is</em> just the
     * word in the cell. Refusing the row would mean creating twelve categories by hand before a
     * file could be uploaded at all.
     *
     * <p><strong>A name that already exists is a duplicate, and the row is skipped.</strong> An
     * expense item has no natural key, but its name is its identity in a budget -- there is one
     * "Netflix" -- so this is what makes re-uploading an overlapping file safe rather than a way to
     * pay for everything twice. The consequence, which is deliberate for now: an import cannot
     * <em>update</em> an existing row. Changing the amount is an edit, and the edit dialog is what
     * it is for.
     */
    @Transactional
    public CsvImportResult importCsv(MultipartFile file) {
        String ownerSub = currentUser.requireSubject();
        CsvFile csv = CsvFile.read(file, CSV_COLUMNS);
        Set<String> written = new HashSet<>();
        Map<String, ExpenseCategory> resolvedTags = new HashMap<>();
        int processed = 0;
        int failed = 0;
        int duplicates = 0;

        for (CsvRow row : csv.rows()) {
            try {
                String name = row.requireText("name").trim();
                if (!written.add(name.toLowerCase(Locale.ROOT))
                        || repository.existsByOwnerSubAndNameIgnoreCase(ownerSub, name)) {
                    duplicates++;
                    continue;
                }

                ExpenseItem item = new ExpenseItem(
                        ownerSub,
                        name,
                        row.requireNonNegative("amount"),
                        Units.normalise(row.requireText("unit")),
                        interval(row),
                        period(row),
                        row.dateOr("startDate", null),
                        row.dateOr("expiryDate", null));
                validateDatesInRow(row, item.getStartDate(), item.getExpiryDate());
                item.replaceCategories(tags(row, ownerSub, resolvedTags));

                repository.save(item);
                processed++;
            } catch (CsvRowException ex) {
                failed++;
                log.debug("Skipped expense item CSV row: {}", ex.getMessage());
            }
        }

        log.info(
                "Imported {} of {} expense item rows ({} duplicates)",
                processed,
                csv.rows().size(),
                duplicates);
        return new CsvImportResult(csv.rows().size(), processed, failed, duplicates);
    }

    /**
     * Tags for one row. Resolved names are remembered for the rest of the file, so a file where
     * forty rows are tagged "Household" costs one lookup and creates one category rather than
     * racing itself to create forty.
     */
    private Set<ExpenseCategory> tags(CsvRow row, String ownerSub, Map<String, ExpenseCategory> resolved) {
        String cell = row.text("categories");
        if (cell == null) {
            return Set.of();
        }
        Set<ExpenseCategory> tags = new LinkedHashSet<>();
        for (String name : cell.split(CATEGORY_SEPARATOR)) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            tags.add(resolved.computeIfAbsent(
                    trimmed.toLowerCase(Locale.ROOT), key -> categories.resolveOrCreateByName(trimmed, ownerSub)));
        }
        return tags;
    }

    /** A blank interval means every period -- the common case, so it need not be in the file. */
    private static int interval(CsvRow row) {
        String raw = row.text("interval");
        if (raw == null) {
            return 1;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw new CsvRowException(row.lineNumber(), "'interval' is not a whole number: '%s'".formatted(raw));
        }
        if (value < 1) {
            throw new CsvRowException(row.lineNumber(), "'interval' must be at least 1");
        }
        return value;
    }

    private static RecurrencePeriod period(CsvRow row) {
        String raw = row.requireText("period");
        String key = raw.trim().toLowerCase(Locale.ROOT);
        RecurrencePeriod period = PERIOD_ALIASES.get(key);
        if (period == null) {
            throw new CsvRowException(
                    row.lineNumber(),
                    "'period' must be one of DAILY, WEEKLY, MONTHLY, YEARLY -- got '%s'".formatted(raw));
        }
        return period;
    }

    /**
     * A row that expires before it starts is a typo, not a zero-length expense, so it is refused
     * rather than stored as something no arithmetic can make sense of.
     */
    private static void validateDates(LocalDate startDate, LocalDate expiryDate) {
        if (startDate != null && expiryDate != null && expiryDate.isBefore(startDate)) {
            throw new IllegalArgumentException("expiryDate cannot be before startDate");
        }
    }

    private static void validateDatesInRow(CsvRow row, LocalDate startDate, LocalDate expiryDate) {
        if (startDate != null && expiryDate != null && expiryDate.isBefore(startDate)) {
            throw new CsvRowException(row.lineNumber(), "'expiryDate' is before 'startDate'");
        }
    }
}
