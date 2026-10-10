/**
 * Turning a flat list of payments into the month-by-month grid the user keeps in a spreadsheet.
 *
 * Everything here is pure: the page hands it whatever the API returned and gets back the grid it
 * renders. No dates are parsed into `Date` - payment dates arrive as ISO strings, and month keys
 * ('2026-09') sort, compare and subtract correctly as text and integers, which keeps the whole
 * file free of timezone questions that a grid of calendar months has no business asking.
 */

import { Dividend } from './dividend.models';

/** One column: a calendar month inside the grid's span. */
export interface MonthColumn {
  /** '2026-09'. Addresses the column, and doubles as the chart's x-axis label. */
  key: string;
  year: number;
  /** 1-12. */
  month: number;
  /** January, or the leftmost column - where the grid draws a heavier separator. */
  startsYear: boolean;
}

/** A sum in one currency. A cell holds one of these per currency it happens to mix. */
export interface Money {
  unit: string;
  amount: number;
}

/**
 * What a (row, month) intersection holds. The footer totals hold the same thing, so one renderer
 * and one tooltip builder serve both.
 */
export interface MoneyCell {
  /** The amounts as received, one entry per currency, biggest first. Never empty. */
  originals: Money[];
  /** The convertible payments summed in `valueUnit`. null when none of them converted. */
  converted: number | null;
  /**
   * Some payment in here had no rate, so `converted` is a lower bound. Never expressed by
   * quietly adding 0 - "not convertible" and "worth nothing" are different answers.
   */
  partial: boolean;
  valueUnit: string;
  /** How many payments were folded in. Drives the multi-payment marker. */
  count: number;
  /** The payments themselves, oldest first, for the tooltip breakdown. */
  items: Dividend[];
}

/** A cell with the column it sits under, so templates never index a parallel array. */
export interface MatrixCell {
  column: MonthColumn;
  cell: MoneyCell | null;
}

export interface MatrixRow {
  /** Stable across rebuilds, for @for tracking. */
  key: string;
  /** The ticker, or null in compact mode where the company lives in the cell's tooltip. */
  symbol: string | null;
  name: string | null;
  cells: MatrixCell[];
  /** Everything this company was paid in range. null in compact mode: a slot has no identity. */
  total: MoneyCell | null;
}

export interface DividendMatrix {
  columns: MonthColumn[];
  rows: MatrixRow[];
  /** One per column, in the same order. */
  totals: MatrixCell[];
  grandTotal: MoneyCell | null;
  valueUnit: string;
  /** Payments that made it into the grid. */
  count: number;
}

export interface MatrixOptions {
  from: string | null;
  to: string | null;
  valueUnit: string;
  /** Drop the per-company rows and pack the payments into anonymous slots. */
  compact: boolean;
}

/** A month's bar on the chart. */
export interface MonthPoint {
  key: string;
  /** null renders as a gap, not as a zero-height bar. */
  value: number | null;
  /** The value is a lower bound because something in the month had no rate. */
  partial: boolean;
}

const EMPTY: DividendMatrix = {
  columns: [],
  rows: [],
  totals: [],
  grandTotal: null,
  valueUnit: '',
  count: 0,
};

/** '2026-09-15' -> '2026-09'. */
export function monthKey(isoDate: string): string {
  return isoDate.slice(0, 7);
}

/** Months since year 0, so a span is plain subtraction. */
function monthIndex(key: string): number {
  return Number(key.slice(0, 4)) * 12 + (Number(key.slice(5, 7)) - 1);
}

function columnAt(index: number, first: number): MonthColumn {
  const year = Math.floor(index / 12);
  const month = (index % 12) + 1;
  return {
    key: `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}`,
    year,
    month,
    startsYear: month === 1 || index === first,
  };
}

/**
 * One cell out of the payments that fall in it.
 *
 * A cell mixing currencies is not an anomaly worth flattening: a broker converting one payout to
 * HUF and leaving the next in USD is exactly the case the `unit` column exists for. So the
 * originals stay per-currency and only the converted figure is a single number.
 */
function toCell(items: Dividend[], valueUnit: string): MoneyCell | null {
  if (items.length === 0) {
    return null;
  }

  const byUnit = new Map<string, number>();
  let converted: number | null = null;
  let unconvertible = 0;

  for (const item of items) {
    byUnit.set(item.unit, (byUnit.get(item.unit) ?? 0) + item.amount);
    if (item.currentValue === null) {
      unconvertible += 1;
    } else {
      converted = (converted ?? 0) + item.currentValue;
    }
  }

  const originals = [...byUnit]
    .map(([unit, amount]): Money => ({ unit, amount }))
    .sort((a, b) => b.amount - a.amount || a.unit.localeCompare(b.unit));

  return {
    originals,
    converted,
    partial: unconvertible > 0 && converted !== null,
    valueUnit,
    count: items.length,
    items: [...items].sort((a, b) => a.paymentDate.localeCompare(b.paymentDate)),
  };
}

/** Biggest first, with the cells that carry no rate at all pushed to the bottom of the column. */
function byWeight(a: MoneyCell, b: MoneyCell): number {
  if (a.converted === null || b.converted === null) {
    if (a.converted !== b.converted) {
      return a.converted === null ? 1 : -1;
    }
    return b.originals[0].amount - a.originals[0].amount;
  }
  return b.converted - a.converted;
}

interface Bucket {
  symbol: string;
  name: string;
  /** Parallel to the columns. */
  perMonth: Dividend[][];
  all: Dividend[];
}

export function buildMatrix(dividends: Dividend[], options: MatrixOptions): DividendMatrix {
  const { valueUnit } = options;
  if (dividends.length === 0) {
    return { ...EMPTY, valueUnit };
  }

  /*
   * The grid spans the *data*, clipped to the requested range - never the range itself. A
   * from-date of 1900 would otherwise ask the browser to lay out fifteen hundred empty columns,
   * and empty months inside the data are the ones worth seeing anyway.
   */
  let first = monthKey(dividends[0].paymentDate);
  let last = first;
  for (const dividend of dividends) {
    const key = monthKey(dividend.paymentDate);
    if (key < first) {
      first = key;
    }
    if (key > last) {
      last = key;
    }
  }
  if (options.from && monthKey(options.from) > first) {
    first = monthKey(options.from);
  }
  if (options.to && monthKey(options.to) < last) {
    last = monthKey(options.to);
  }
  if (first > last) {
    return { ...EMPTY, valueUnit };
  }

  const firstIndex = monthIndex(first);
  const lastIndex = monthIndex(last);
  const columns: MonthColumn[] = [];
  for (let index = firstIndex; index <= lastIndex; index += 1) {
    columns.push(columnAt(index, firstIndex));
  }
  const columnOf = new Map(columns.map((column, index) => [column.key, index]));

  const buckets = new Map<number, Bucket>();
  const perColumn: Dividend[][] = columns.map(() => []);
  const everything: Dividend[] = [];

  for (const dividend of dividends) {
    const index = columnOf.get(monthKey(dividend.paymentDate));
    if (index === undefined) {
      continue;
    }

    let bucket = buckets.get(dividend.instrumentId);
    if (!bucket) {
      bucket = {
        symbol: dividend.instrumentSymbol,
        name: dividend.instrumentName,
        perMonth: columns.map(() => []),
        all: [],
      };
      buckets.set(dividend.instrumentId, bucket);
    }

    bucket.perMonth[index].push(dividend);
    bucket.all.push(dividend);
    perColumn[index].push(dividend);
    everything.push(dividend);
  }

  const rows = options.compact
    ? packedRows(columns, buckets, valueUnit)
    : companyRows(columns, buckets, valueUnit);

  return {
    columns,
    rows,
    totals: columns.map((column, index) => ({
      column,
      cell: toCell(perColumn[index], valueUnit),
    })),
    grandTotal: toCell(everything, valueUnit),
    valueUnit,
    count: everything.length,
  };
}

/** One row per company, ticker ascending - the layout the spreadsheet has. */
function companyRows(
  columns: MonthColumn[],
  buckets: Map<number, Bucket>,
  valueUnit: string,
): MatrixRow[] {
  return [...buckets]
    .sort(([, a], [, b]) => a.symbol.localeCompare(b.symbol))
    .map(([instrumentId, bucket]) => ({
      key: String(instrumentId),
      symbol: bucket.symbol,
      name: bucket.name,
      cells: columns.map((column, index) => ({
        column,
        cell: toCell(bucket.perMonth[index], valueUnit),
      })),
      total: toCell(bucket.all, valueUnit),
    }));
}

/**
 * Compact: the company stops being a row and becomes a tooltip.
 *
 * Each month column is packed top-down with its own payments, biggest first, so forty companies
 * that were never paid in the same month collapse into a handful of rows. Nothing is dropped and
 * nothing is summed away - a cell still holds exactly one company's month, which is what keeps
 * the tooltips meaningful. A row is a slot, not an entity: reading across one means nothing, so
 * there is deliberately no row total.
 */
function packedRows(
  columns: MonthColumn[],
  buckets: Map<number, Bucket>,
  valueUnit: string,
): MatrixRow[] {
  const stacks = columns.map((_, index) =>
    [...buckets.values()]
      .map((bucket) => toCell(bucket.perMonth[index], valueUnit))
      .filter((cell): cell is MoneyCell => cell !== null)
      .sort(byWeight),
  );

  const depth = stacks.reduce((deepest, stack) => Math.max(deepest, stack.length), 0);

  return Array.from({ length: depth }, (_unused, slot) => ({
    key: `slot-${slot}`,
    symbol: null,
    name: null,
    cells: columns.map((column, index) => ({ column, cell: stacks[index][slot] ?? null })),
    total: null,
  }));
}

/**
 * The bars.
 *
 * With a currency filter on, every payment is already in one real currency, so the chart plots
 * those amounts and needs no exchange rate at all. Without one, the months mix currencies and the
 * only honest y-axis is the converted one.
 */
export function chartSeries(matrix: DividendMatrix, unitFilter: string | null): MonthPoint[] {
  const unit = unitFilter?.trim().toUpperCase() || null;

  return matrix.totals.map(({ column, cell }) => {
    if (cell === null) {
      return { key: column.key, value: null, partial: false };
    }
    if (unit !== null) {
      const match = cell.originals.find((money) => money.unit === unit);
      return { key: column.key, value: match?.amount ?? null, partial: false };
    }
    return { key: column.key, value: cell.converted, partial: cell.partial };
  });
}

/** What the chart's y-axis is denominated in, given the same filter. */
export function chartUnit(matrix: DividendMatrix, unitFilter: string | null): string {
  return unitFilter?.trim().toUpperCase() || matrix.valueUnit;
}
