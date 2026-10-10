import { ExpenseCategory, ExpenseItem, RecurrencePeriod } from './expense.models';

/**
 * Turning recurrences into comparable figures, and the presets the form offers.
 *
 * This is the one piece of arithmetic the backend deliberately does not do. Normalising "every 2
 * weeks" into a figure per month needs an average month, and an average month is a presentation
 * choice rather than a fact - so it is made here, once, next to the selector that decides which
 * period the user is looking at. Everything in this file is pure, which is what makes it the
 * obvious first thing to cover once Vitest is set up.
 */

/**
 * How long each period is, in days.
 *
 * 365.2425 is the mean Gregorian year - 365 days plus the leap-year rule - and a month is a
 * twelfth of it. Using those two rather than 365 and 30 is what makes the conversions consistent
 * with each other: a monthly cost is exactly twelve times smaller than the same cost stated
 * yearly (365.2425 / 30.436875 === 12), so a budget does not change when a row is re-entered in a
 * different period. With 30-day months it would be out by 1.4%.
 *
 * The cost is that "per month" is an average, not a calendar month: 31 daily charges in January
 * show as 30.44 days' worth. That is the right trade for comparing a budget, and it is why this
 * is a UI-side figure and not something stored.
 */
export const PERIOD_DAYS: Record<RecurrencePeriod, number> = {
  DAILY: 1,
  WEEKLY: 7,
  MONTHLY: 365.2425 / 12,
  YEARLY: 365.2425,
};

/** One entry in the "Repeats" dropdown: a label plus the interval+period pair it stores. */
export interface RecurrencePreset {
  /** Also the i18n key suffix: expenses.recurrence.<id>. */
  id: string;
  interval: number;
  period: RecurrencePeriod;
}

/**
 * What the form offers instead of making everybody fill in two fields.
 *
 * The stored shape is still interval + period - a preset is only a pair of values with a name, so
 * nothing is lost by picking one and nothing special happens to a row that was created this way.
 * "Quarterly" being 3 + MONTHLY rather than its own period is the whole reason this list exists
 * outside the API.
 */
export const RECURRENCE_PRESETS: readonly RecurrencePreset[] = [
  { id: 'daily', interval: 1, period: 'DAILY' },
  { id: 'weekly', interval: 1, period: 'WEEKLY' },
  { id: 'biweekly', interval: 2, period: 'WEEKLY' },
  { id: 'monthly', interval: 1, period: 'MONTHLY' },
  { id: 'quarterly', interval: 3, period: 'MONTHLY' },
  { id: 'halfYearly', interval: 6, period: 'MONTHLY' },
  { id: 'yearly', interval: 1, period: 'YEARLY' },
];

/** The escape hatch: picking it reveals the interval and period fields themselves. */
export const CUSTOM_RECURRENCE = 'custom';

/**
 * Which preset a stored recurrence corresponds to, or CUSTOM_RECURRENCE when none does.
 *
 * This is what lets the dropdown be the primary control without hiding anything: an "every 7
 * weeks" row typed by hand, or imported from a CSV, opens the dialog on Custom with its real
 * values rather than being silently rounded to the nearest preset.
 */
export function presetIdFor(interval: number, period: RecurrencePeriod): string {
  const match = RECURRENCE_PRESETS.find(
    (preset) => preset.interval === interval && preset.period === period,
  );
  return match ? match.id : CUSTOM_RECURRENCE;
}

export function presetById(id: string): RecurrencePreset | null {
  return RECURRENCE_PRESETS.find((preset) => preset.id === id) ?? null;
}

/**
 * What one expense costs per `target` period, in its own currency.
 *
 * Converting into a common currency is a separate step - see {@link perPeriodIn} - because it can
 * fail, and this cannot.
 */
export function perPeriod(item: ExpenseItem, target: RecurrencePeriod): number {
  const interval = Math.max(1, item.recurrenceInterval);
  // Same period as the row: plain division, with no detour through an average month. Going the
  // long way round gives 9.999999999999998 for a monthly 10 shown per month - invisible once it
  // is rounded for display, but a figure somebody is checking against their bank statement should
  // be exactly what they typed, and the commonest case deserves the exact answer.
  if (item.recurrencePeriod === target) {
    return item.amount / interval;
  }
  const everyDays = interval * PERIOD_DAYS[item.recurrencePeriod];
  return (item.amount * PERIOD_DAYS[target]) / everyDays;
}

/* ------------------------------------------------------------------ *
 * Currency conversion
 * ------------------------------------------------------------------ */

/** The sentinel for "don't convert" - see {@link totalsByUnit} for why it is not null. */
export const AS_ENTERED = 'ORIGINAL';

/** One stored quote. Structurally what the wealth slice's ExchangeRate already is. */
export interface RateQuote {
  sourceUnit: string;
  destinationUnit: string;
  rate: number;
  /** ISO-8601. Only used to decide which of two quotes for a pair is the newer. */
  rateDate: string;
}

/** Resolved factors, keyed "FROM>TO". Built once per rate load, read once per row. */
export type RateTable = ReadonlyMap<string, number>;

/**
 * The newest quote per pair, as a lookup.
 *
 * The backend's `/exchange-rates/latest` already returns one row per pair, so the date comparison
 * here is belt and braces - but it costs nothing and means this function is correct for any list
 * of quotes, including the unfiltered one.
 *
 * A rate of zero is dropped rather than stored: it cannot convert anything, and keeping it would
 * turn every amount into 0, which is exactly the "a missing rate must never look like a real
 * number" mistake the backend's valuation takes such care to avoid.
 */
export function buildRateTable(quotes: readonly RateQuote[]): RateTable {
  const newest = new Map<string, { rate: number; date: string }>();
  for (const quote of quotes) {
    if (!quote.rate) {
      continue;
    }
    const key = `${quote.sourceUnit}>${quote.destinationUnit}`;
    const held = newest.get(key);
    if (!held || held.date < quote.rateDate) {
      newest.set(key, { rate: quote.rate, date: quote.rateDate });
    }
  }
  return new Map([...newest].map(([key, held]) => [key, held.rate]));
}

/**
 * Converts an amount between units, or returns null when no stored rate connects them.
 *
 * Three steps, deliberately the same three the backend's `ExchangeRateService.resolveFactor` uses,
 * so a figure on this screen and the same figure on the wealth screen cannot disagree about what
 * connects two units: the same unit converts one-to-one, a directly quoted pair wins next, and
 * finally the reciprocal of the opposite quote is used.
 *
 * There is deliberately **no chaining** through a third currency. The backend does not do it
 * either, and a EUR figure silently routed through HUF would be quietly less trustworthy than the
 * two quotes it was built from - null says "I cannot answer that" instead, and the screen renders
 * an em dash and marks the total as a lower bound.
 */
export function convert(
  amount: number,
  from: string,
  to: string,
  rates: RateTable | null,
): number | null {
  if (from === to) {
    return amount;
  }
  if (!rates) {
    return null;
  }
  const direct = rates.get(`${from}>${to}`);
  if (direct !== undefined) {
    return amount * direct;
  }
  const opposite = rates.get(`${to}>${from}`);
  if (opposite !== undefined && opposite !== 0) {
    return amount / opposite;
  }
  return null;
}

/** Every unit any stored quote mentions, so the picker offers what can actually be converted to. */
export function unitsInRates(rates: RateTable | null): string[] {
  if (!rates) {
    return [];
  }
  const units = new Set<string>();
  for (const key of rates.keys()) {
    const [from, to] = key.split('>');
    units.add(from);
    units.add(to);
  }
  return [...units].sort((a, b) => a.localeCompare(b));
}

/**
 * What one expense costs per `target` period, expressed in `valueIn`.
 *
 * `AS_ENTERED` means "leave it in its own currency", which always has an answer. A real currency
 * that no rate connects to the row's own gives null - never zero, because "not convertible" and
 * "costs nothing" are different answers, as everywhere else in this application.
 */
export function perPeriodIn(
  item: ExpenseItem,
  target: RecurrencePeriod,
  valueIn: string,
  rates: RateTable | null,
): number | null {
  const own = perPeriod(item, target);
  return valueIn === AS_ENTERED ? own : convert(own, item.unit, valueIn, rates);
}

/** Which currency a row's normalised figure is shown in. */
export function displayUnit(item: ExpenseItem, valueIn: string): string {
  return valueIn === AS_ENTERED ? item.unit : valueIn;
}

/** A subtotal. `partial` marks a sum that left something out for want of a rate. */
export interface UnitTotal {
  unit: string;
  total: number;
  /** True when some row could not be converted, so the figure is a lower bound. */
  partial: boolean;
}

/**
 * Sums the given items, normalised to `target`.
 *
 * The caller decides which items to pass - the page totals only the active ones, because "what do
 * I pay regularly" should not include a loan that finished in 2021.
 *
 * With `valueIn` set to {@link AS_ENTERED} the result is **one row per currency**, added up
 * without any exchange rate: a list of bills reads perfectly well that way, and running it
 * through a rate that moves daily would make last month's total change when nothing did. With a
 * real currency it is a single row, and any item no rate could reach is left out and flagged with
 * `partial` rather than dropped silently or counted as zero - the same `>=` the wealth and
 * dividend screens show.
 *
 * When nothing could be converted at all the result is empty rather than a zero, so the screen
 * shows an em dash: there is no answer here, not an answer of none.
 */
export function totalsByUnit(
  items: readonly ExpenseItem[],
  target: RecurrencePeriod,
  valueIn: string = AS_ENTERED,
  rates: RateTable | null = null,
): UnitTotal[] {
  if (valueIn !== AS_ENTERED) {
    let total: number | null = null;
    let partial = false;
    for (const item of items) {
      const value = perPeriodIn(item, target, valueIn, rates);
      if (value === null) {
        partial = true;
      } else {
        total = (total ?? 0) + value;
      }
    }
    return total === null ? [] : [{ unit: valueIn, total, partial }];
  }

  const totals = new Map<string, number>();
  for (const item of items) {
    totals.set(item.unit, (totals.get(item.unit) ?? 0) + perPeriod(item, target));
  }
  return [...totals.entries()]
    .map(([unit, total]) => ({ unit, total, partial: false }))
    .sort((a, b) => a.unit.localeCompare(b.unit));
}

/* ------------------------------------------------------------------ *
 * Share of the total, for the pie
 * ------------------------------------------------------------------ */

/** Above this many priced rows, the tail is summed into one "Other" slice. */
export const MAX_PIE_SLICES = 25;

/** A slice thinner than this is folded into "Other" whatever the count. */
export const MIN_PIE_SHARE = 0.01;

/**
 * Never folded by the share rule, however small.
 *
 * Without it, a list of two hundred equal expenses would put every one of them under the minimum
 * share and leave a single wedge labelled "Other, 100%" - a chart that has thrown away its own
 * subject.
 */
const ALWAYS_KEPT = 5;

/** One wedge. `share` is 0..1 of the pie's own total, not of anything larger. */
export interface ShareSlice {
  name: string;
  value: number;
  share: number;
  /** True for the summed tail, which is drawn in a neutral colour rather than a magnitude one. */
  other: boolean;
}

/** What the pie can honestly draw, and what it had to leave out to do it. */
export interface ShareBreakdown {
  /**
   * The currency every slice is in, or null when these rows cannot share one pie - which happens
   * when nothing is being converted and the rows are in more than one currency. A pie is a
   * part-to-whole picture, and there is no whole to be part of until the parts are comparable.
   */
  unit: string | null;
  slices: ShareSlice[];
  total: number;
  /** Rows dropped because no stored rate reaches the chosen currency. Reported, never counted. */
  unpriced: number;
  /** How many rows the "Other" slice is made of. */
  folded: number;
}

/**
 * Turns the visible expenses into pie slices, biggest first.
 *
 * Two folding rules, both of them "sum the smallest values as Other": the tail past
 * {@link MAX_PIE_SLICES}, and anything thinner than {@link MIN_PIE_SHARE}. The second is what
 * actually keeps the picture readable - a real budget has a long tail of sub-1% rows whose wedges
 * are a pixel wide and whose labels collide - and the first is the backstop for a list that is
 * long but evenly spread.
 *
 * A fold of exactly one row is undone: an "Other" made of a single expense is that expense with
 * its name taken away.
 *
 * Rows worth zero are left out silently. A zero-width wedge is not a thing a pie can show, and
 * counting it would only add a legend entry nobody can point at.
 */
export function shareBreakdown(
  items: readonly ExpenseItem[],
  target: RecurrencePeriod,
  valueIn: string,
  rates: RateTable | null,
  otherLabel: string,
  maxSlices: number = MAX_PIE_SLICES,
  minShare: number = MIN_PIE_SHARE,
): ShareBreakdown {
  const units = new Set(items.map((item) => item.unit));
  const unit = valueIn !== AS_ENTERED ? valueIn : units.size === 1 ? [...units][0] : null;
  const empty: ShareBreakdown = { unit, slices: [], total: 0, unpriced: 0, folded: 0 };
  if (unit === null) {
    return empty;
  }

  let unpriced = 0;
  const priced: { name: string; value: number }[] = [];
  for (const item of items) {
    const value = perPeriodIn(item, target, valueIn, rates);
    if (value === null) {
      unpriced++;
    } else if (value > 0) {
      priced.push({ name: item.name, value });
    }
  }
  if (priced.length === 0) {
    return { ...empty, unpriced };
  }

  priced.sort((a, b) => b.value - a.value);
  const total = priced.reduce((sum, row) => sum + row.value, 0);

  // One place is reserved for "Other", so the pie never shows maxSlices + 1 wedges.
  const keepLimit = Math.max(1, maxSlices - 1);
  const kept: { name: string; value: number }[] = [];
  let folding: { name: string; value: number }[] = [];
  priced.forEach((row, index) => {
    const tooMany = index >= keepLimit;
    const tooThin = index >= ALWAYS_KEPT && row.value / total < minShare;
    if (tooMany || tooThin) {
      folding.push(row);
    } else {
      kept.push(row);
    }
  });
  if (folding.length === 1) {
    kept.push(folding[0]);
    folding = [];
  }

  const slices: ShareSlice[] = kept.map((row) => ({
    name: row.name,
    value: row.value,
    share: row.value / total,
    other: false,
  }));
  if (folding.length > 0) {
    const value = folding.reduce((sum, row) => sum + row.value, 0);
    slices.push({ name: otherLabel, value, share: value / total, other: true });
  }

  return { unit, slices, total, unpriced, folded: folding.length };
}

/** One parent row of the grouped view: a category, or the bucket for untagged items. */
export interface CategoryGroup {
  /** Stable tree-node key. The untagged bucket has no id, hence the string. */
  key: string;
  /** null for the untagged bucket. */
  category: ExpenseCategory | null;
  items: ExpenseItem[];
  /** Subtotals over this group's active items, normalised to the chosen period. */
  totals: UnitTotal[];
}

/** Key of the bucket collecting items that carry no tag at all. */
export const UNCATEGORISED_KEY = 'uncategorised';

/**
 * Groups items by category for the collected view.
 *
 * Because categories are tags rather than a single column, **an item carrying two of them appears
 * under both**, and the subtotals therefore overlap. That is the honest rendering of a tag model:
 * the alternative - picking one tag per item to count it under - would invent an answer the data
 * does not contain. The page shows the un-grouped total beside these so the double counting is
 * visible rather than implied.
 *
 * Empty categories are left out: a tag nobody has used yet belongs on the categories screen, not
 * as a row of zeros here.
 */
export function groupByCategory(
  items: readonly ExpenseItem[],
  target: RecurrencePeriod,
  valueIn: string = AS_ENTERED,
  rates: RateTable | null = null,
): CategoryGroup[] {
  const groups = new Map<string, CategoryGroup>();
  const untagged: ExpenseItem[] = [];

  for (const item of items) {
    if (item.categories.length === 0) {
      untagged.push(item);
      continue;
    }
    for (const category of item.categories) {
      const key = String(category.id);
      const group = groups.get(key);
      if (group) {
        group.items.push(item);
      } else {
        groups.set(key, { key, category, items: [item], totals: [] });
      }
    }
  }

  const ordered = [...groups.values()].sort((a, b) =>
    (a.category?.category ?? '').localeCompare(b.category?.category ?? ''),
  );
  if (untagged.length > 0) {
    // Last, and not sorted in among the named ones: it is a leftovers bucket, not a category.
    ordered.push({ key: UNCATEGORISED_KEY, category: null, items: untagged, totals: [] });
  }

  for (const group of ordered) {
    group.totals = totalsByUnit(
      group.items.filter((item) => item.active),
      target,
      valueIn,
      rates,
    );
  }
  return ordered;
}
