package com.stockmarketanalyzer.backend.wealth.service;

import com.stockmarketanalyzer.backend.common.ResourceConflictException;
import com.stockmarketanalyzer.backend.common.ResourceNotFoundException;
import com.stockmarketanalyzer.backend.common.Units;
import com.stockmarketanalyzer.backend.security.CurrentUser;
import com.stockmarketanalyzer.backend.wealth.domain.WealthEntry;
import com.stockmarketanalyzer.backend.wealth.repository.WealthEntryRepository;
import com.stockmarketanalyzer.backend.wealth.repository.WealthSpecifications;
import com.stockmarketanalyzer.backend.wealth.web.dto.WealthEntryCloneOverride;
import com.stockmarketanalyzer.backend.wealth.web.dto.WealthEntryCloneRequest;
import com.stockmarketanalyzer.backend.wealth.web.dto.WealthEntryRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD over the owner's wealth snapshot rows, plus the on-read valuation that replaces the
 * stored "current value" column: nothing is persisted about worth, so editing a rate re-values
 * every snapshot that depends on it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WealthEntryService {

    /** What entries are valued in when the caller does not say. */
    public static final String DEFAULT_VALUE_UNIT = "HUF";

    /** Belt and braces against a parent chain corrupted into a loop by direct DB edits. */
    private static final int MAX_PARENT_DEPTH = 32;

    private static final int VALUE_SCALE = 6;

    private final WealthEntryRepository repository;
    private final ExchangeRateService exchangeRates;
    private final CurrentUser currentUser;

    public Page<WealthEntry> search(
            LocalDate from,
            LocalDate to,
            String unit,
            Long parentId,
            boolean rootsOnly,
            String search,
            Pageable pageable) {
        return repository.findAll(
                WealthSpecifications.entries(
                        currentUser.requireSubject(), from, to, unit, parentId, rootsOnly, search),
                pageable);
    }

    /** Every row of the most recent snapshot the owner recorded. Empty when there is none. */
    public List<WealthEntry> latestSnapshot() {
        String ownerSub = currentUser.requireSubject();
        return repository
                .findFirstByOwnerSubOrderByEntryDateDesc(ownerSub)
                .map(newest -> repository.findByOwnerSubAndEntryDateOrderByIdAsc(ownerSub, newest.getEntryDate()))
                .orElseGet(List::of);
    }

    public List<WealthEntry> children(Long parentId) {
        WealthEntry parent = get(parentId);
        return repository.findByOwnerSubAndParentIdOrderByIdAsc(parent.getOwnerSub(), parent.getId());
    }

    public WealthEntry get(Long id) {
        return repository
                .findByIdAndOwnerSub(id, currentUser.requireSubject())
                .orElseThrow(() -> notFound(id));
    }

    @Transactional
    public WealthEntry create(WealthEntryRequest request) {
        String ownerSub = currentUser.requireSubject();
        WealthEntry entry = new WealthEntry(
                ownerSub,
                request.name().trim(),
                request.unit(),
                request.quantity(),
                request.entryDate() != null ? request.entryDate() : LocalDate.now());
        entry.setParent(resolveParent(ownerSub, request.parentId(), null));
        WealthEntry saved = repository.save(entry);
        log.debug("Created wealth entry {} ({} {}) for {}",
                saved.getId(), saved.getQuantity(), saved.getUnit(), saved.getEntryDate());
        return saved;
    }

    @Transactional
    public WealthEntry update(Long id, WealthEntryRequest request) {
        WealthEntry entry = get(id);
        entry.setName(request.name().trim());
        entry.setUnit(request.unit());
        entry.setQuantity(request.quantity());
        if (request.entryDate() != null) {
            entry.setEntryDate(request.entryDate());
        }
        entry.setParent(resolveParent(entry.getOwnerSub(), request.parentId(), entry));
        return repository.save(entry);
    }

    /**
     * Copies an entry and everything under it into a new snapshot: same shape, same names, one
     * new date, and whatever quantities and units the caller supplies.
     *
     * <p>It is one transaction on purpose. Recording a month by hand means a create per row and
     * a re-parent per row, and a failure half way through leaves a subtree that is neither the
     * old snapshot nor the new one; here nothing is written unless every row is.
     *
     * <p>The copy hangs where the source hangs -- same parent -- because a clone is the same
     * holding recorded again, not a move. Put it somewhere else by editing the copy afterwards.
     */
    @Transactional
    public List<WealthEntry> cloneSubtree(Long id, WealthEntryCloneRequest request) {
        WealthEntry source = get(id);
        Map<Long, List<WealthEntry>> childrenByParent =
                indexChildren(repository.findByOwnerSubOrderByIdAsc(source.getOwnerSub()));

        List<WealthEntry> sources = new ArrayList<>();
        collectSubtree(source, childrenByParent, sources, new HashSet<>(), 0);
        Set<Long> cloneable = sources.stream().map(WealthEntry::getId).collect(Collectors.toSet());

        // Validated up front rather than as the rows are written: a typo in an id should be a
        // 400 that changed nothing, not a rollback of half a snapshot.
        List<WealthEntryCloneOverride> requested =
                request.overrides() != null ? request.overrides() : List.of();
        Map<Long, WealthEntryCloneOverride> overrides = new LinkedHashMap<>();
        for (WealthEntryCloneOverride override : requested) {
            if (!cloneable.contains(override.sourceId())) {
                throw new IllegalArgumentException(
                        "Entry %d is not part of the subtree under %d, so it cannot be overridden here"
                                .formatted(override.sourceId(), id));
            }
            if (overrides.put(override.sourceId(), override) != null) {
                throw new IllegalArgumentException(
                        "Entry %d was overridden twice in the same clone".formatted(override.sourceId()));
            }
        }

        LocalDate entryDate = request.entryDate() != null ? request.entryDate() : LocalDate.now();
        List<WealthEntry> created = new ArrayList<>();
        copyInto(
                source, source.getParent(), null, entryDate, overrides, childrenByParent, created, new HashSet<>(), 0);

        log.info("Cloned wealth entry {} into {} row(s) dated {}", id, created.size(), entryDate);
        return created;
    }

    /**
     * Writes one copy and then its children, depth first, saving as it goes: the child needs its
     * parent's generated id, so the parent has to reach the database first.
     */
    private void copyInto(
            WealthEntry source,
            WealthEntry parent,
            String inheritedUnit,
            LocalDate entryDate,
            Map<Long, WealthEntryCloneOverride> overrides,
            Map<Long, List<WealthEntry>> childrenByParent,
            List<WealthEntry> created,
            Set<Long> visiting,
            int depth) {
        if (source.getId() == null || depth > MAX_PARENT_DEPTH || !visiting.add(source.getId())) {
            return;
        }

        String unit = source.getUnit();
        BigDecimal quantity = source.getQuantity();

        WealthEntryCloneOverride override = overrides.get(source.getId());
        if (override != null) {
            // A blank unit is the form saying "same as the row above", so the root's unit reaches
            // the leaves without being retyped on every line. Mixed-unit trees still work: an
            // override that names a unit keeps it, and the roll-up sums value, not quantity.
            unit = override.unit() == null || override.unit().isBlank()
                    ? (inheritedUnit != null ? inheritedUnit : source.getUnit())
                    : override.unit();
            // Taken as given, null included: that is how a holding is recorded as an aggregate.
            quantity = override.quantity();
        }

        WealthEntry copy =
                new WealthEntry(source.getOwnerSub(), source.getName(), unit, quantity, entryDate);
        copy.setParent(parent);
        WealthEntry saved = repository.save(copy);
        created.add(saved);

        for (WealthEntry child : childrenByParent.getOrDefault(source.getId(), List.of())) {
            copyInto(
                    child,
                    saved,
                    saved.getUnit(),
                    entryDate,
                    overrides,
                    childrenByParent,
                    created,
                    visiting,
                    depth + 1);
        }
    }

    /** The entry plus every descendant, parents before children. */
    private void collectSubtree(
            WealthEntry node,
            Map<Long, List<WealthEntry>> childrenByParent,
            List<WealthEntry> into,
            Set<Long> visiting,
            int depth) {
        if (node.getId() == null || depth > MAX_PARENT_DEPTH || !visiting.add(node.getId())) {
            return;
        }
        into.add(node);
        for (WealthEntry child : childrenByParent.getOrDefault(node.getId(), List.of())) {
            collectSubtree(child, childrenByParent, into, visiting, depth + 1);
        }
    }

    private static Map<Long, List<WealthEntry>> indexChildren(Collection<WealthEntry> forest) {
        Map<Long, List<WealthEntry>> childrenByParent = new HashMap<>();
        for (WealthEntry node : forest) {
            WealthEntry parent = node.getParent();
            if (parent != null) {
                childrenByParent
                        .computeIfAbsent(parent.getId(), key -> new ArrayList<>())
                        .add(node);
            }
        }
        return childrenByParent;
    }

    /**
     * Deleting a node with children is refused unless {@code cascade} is set -- the parent rows
     * are aggregates, and removing one silently takes its accounts with it via the FK.
     */
    @Transactional
    public void delete(Long id, boolean cascade) {
        WealthEntry entry = get(id);
        if (!cascade && repository.existsByParentId(entry.getId())) {
            throw new ResourceConflictException(
                    "Wealth entry %d has child entries. Re-send with cascade=true to delete them as well."
                            .formatted(entry.getId()));
        }
        repository.delete(entry);
        log.info("Deleted wealth entry {} (cascade={})", entry.getId(), cascade);
    }

    /** Convenience wrapper around {@link #valuesIn(Collection, String)} for a single entry. */
    public BigDecimal valueIn(WealthEntry entry, String targetUnit) {
        return valuesIn(List.of(entry), targetUnit).get(entry.getId());
    }

    /**
     * Values each entry in {@code targetUnit} using the rate in force on that entry's own date.
     * An entry whose unit the owner has never quoted maps to {@code null} rather than zero --
     * "not convertible" and "worth nothing" are different answers.
     *
     * <p>Factors are memoised per (unit, date) pair, so a page of rows sharing a snapshot date
     * costs one rate lookup per distinct unit instead of one per row.
     */
    public Map<Long, BigDecimal> valuesIn(Collection<WealthEntry> entries, String targetUnit) {
        String target = resolveValueUnit(targetUnit);
        String ownerSub = currentUser.requireSubject();
        Map<FactorKey, Optional<BigDecimal>> cache = new HashMap<>();
        Map<Long, BigDecimal> values = new LinkedHashMap<>();

        for (WealthEntry entry : entries) {
            // An aggregate row holds nothing of its own, so there is nothing to convert and
            // no point resolving a factor for it.
            if (entry.getQuantity() == null) {
                values.put(entry.getId(), null);
                continue;
            }
            Optional<BigDecimal> factor = cache.computeIfAbsent(
                    new FactorKey(entry.getUnit(), entry.getEntryDate()),
                    key -> exchangeRates.resolveFactor(ownerSub, key.unit(), target, key.date()));
            values.put(
                    entry.getId(),
                    factor.map(value -> entry.getQuantity()
                                    .multiply(value)
                                    .setScale(VALUE_SCALE, RoundingMode.HALF_UP))
                            .orElse(null));
        }
        return values;
    }

    /** Convenience wrapper around {@link #valuations(Collection, String)} for a single entry. */
    public EntryValuation valuation(WealthEntry entry, String targetUnit) {
        return valuations(List.of(entry), targetUnit).getOrDefault(entry.getId(), EntryValuation.EMPTY);
    }

    /**
     * Values each entry and its whole subtree in {@code targetUnit}.
     *
     * <p>An entry's own quantity and its children both count: a node with a quantity <em>and</em>
     * children is worth its own holding plus everything under it. A node with a null quantity is
     * a pure aggregate and is worth exactly its subtree.
     *
     * <p>The owner's entire forest is loaded once, because a roll-up cannot be computed from a
     * filtered page -- the children needed for the sum may not be in it. The fold is memoised,
     * so the whole forest costs one pass regardless of how many entries were asked about.
     */
    public Map<Long, EntryValuation> valuations(Collection<WealthEntry> entries, String targetUnit) {
        if (entries.isEmpty()) {
            return Map.of();
        }

        String target = resolveValueUnit(targetUnit);
        List<WealthEntry> forest = repository.findByOwnerSubOrderByIdAsc(currentUser.requireSubject());

        Map<Long, WealthEntry> byId =
                forest.stream().collect(Collectors.toMap(WealthEntry::getId, Function.identity(), (a, b) -> a));
        Map<Long, List<WealthEntry>> childrenByParent = indexChildren(forest);

        Map<Long, BigDecimal> ownValues = valuesIn(forest, target);
        Map<Long, SubtreeAggregate> memo = new HashMap<>();

        Map<Long, EntryValuation> valuations = new LinkedHashMap<>();
        for (WealthEntry requested : entries) {
            // Prefer the instance from the forest: the caller's copy may predate it.
            WealthEntry node = byId.getOrDefault(requested.getId(), requested);
            SubtreeAggregate aggregate =
                    fold(node, childrenByParent, ownValues, memo, new HashSet<>(), 0);

            boolean singleUnit = !aggregate.mixed()
                    && aggregate.soleUnit() != null
                    && aggregate.soleUnit().equals(node.getUnit());

            valuations.put(
                    requested.getId(),
                    new EntryValuation(
                            ownValues.get(node.getId()),
                            aggregate.value(),
                            aggregate.partial(),
                            singleUnit ? aggregate.quantity() : null));
        }
        return valuations;
    }

    /**
     * Folds one subtree into a single accumulator. {@code visiting} and {@code depth} are
     * belt and braces: writes are cycle-checked, but a loop introduced by a direct database
     * edit would otherwise recurse forever.
     */
    private SubtreeAggregate fold(
            WealthEntry node,
            Map<Long, List<WealthEntry>> childrenByParent,
            Map<Long, BigDecimal> ownValues,
            Map<Long, SubtreeAggregate> memo,
            Set<Long> visiting,
            int depth) {
        Long id = node.getId();
        if (id == null || depth > MAX_PARENT_DEPTH) {
            return SubtreeAggregate.EMPTY;
        }
        SubtreeAggregate cached = memo.get(id);
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(id)) {
            log.warn("Cycle detected under wealth entry {} while rolling up; subtree skipped", id);
            return SubtreeAggregate.EMPTY;
        }

        BigDecimal value = null;
        boolean partial = false;
        BigDecimal quantity = null;
        String soleUnit = null;
        boolean mixed = false;

        if (node.getQuantity() != null) {
            quantity = node.getQuantity();
            soleUnit = node.getUnit();
            BigDecimal own = ownValues.get(id);
            if (own == null) {
                partial = true;
            } else {
                value = own;
            }
        }

        for (WealthEntry child : childrenByParent.getOrDefault(id, List.of())) {
            SubtreeAggregate childAggregate =
                    fold(child, childrenByParent, ownValues, memo, visiting, depth + 1);

            if (childAggregate.value() != null) {
                value = value == null ? childAggregate.value() : value.add(childAggregate.value());
            }
            partial = partial || childAggregate.partial();

            if (childAggregate.quantity() != null) {
                quantity = quantity == null
                        ? childAggregate.quantity()
                        : quantity.add(childAggregate.quantity());
            }
            if (childAggregate.mixed()) {
                mixed = true;
            } else if (childAggregate.soleUnit() != null) {
                if (soleUnit == null) {
                    soleUnit = childAggregate.soleUnit();
                } else if (!soleUnit.equals(childAggregate.soleUnit())) {
                    mixed = true;
                }
            }
        }

        SubtreeAggregate aggregate = new SubtreeAggregate(value, partial, quantity, soleUnit, mixed);
        visiting.remove(id);
        memo.put(id, aggregate);
        return aggregate;
    }

    /** Normalises the requested valuation unit, falling back to {@link #DEFAULT_VALUE_UNIT}. */
    public static String resolveValueUnit(String requested) {
        return Units.normalise(
                requested == null || requested.isBlank() ? DEFAULT_VALUE_UNIT : requested);
    }

    private WealthEntry resolveParent(String ownerSub, Long parentId, WealthEntry entry) {
        if (parentId == null) {
            return null;
        }
        if (entry != null && parentId.equals(entry.getId())) {
            throw new IllegalArgumentException("A wealth entry cannot be its own parent");
        }
        WealthEntry parent = repository
                .findByIdAndOwnerSub(parentId, ownerSub)
                .orElseThrow(() -> notFound(parentId));
        if (entry != null) {
            guardAgainstCycle(entry, parent);
        }
        return parent;
    }

    private void guardAgainstCycle(WealthEntry entry, WealthEntry proposedParent) {
        WealthEntry ancestor = proposedParent;
        for (int depth = 0; ancestor != null && depth < MAX_PARENT_DEPTH; depth++) {
            if (entry.getId().equals(ancestor.getId())) {
                throw new IllegalArgumentException(
                        "Entry %d cannot be moved under %d: that would create a cycle"
                                .formatted(entry.getId(), proposedParent.getId()));
            }
            ancestor = ancestor.getParent();
        }
    }

    private static ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("No wealth entry found with id %d".formatted(id));
    }

    /** Cache key for a resolved conversion factor within one call. */
    private record FactorKey(String unit, LocalDate date) {}
}
