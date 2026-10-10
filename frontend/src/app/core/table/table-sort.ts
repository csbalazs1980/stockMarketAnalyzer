/**
 * Column sorting shared by every table in the app.
 *
 * Two situations, one vocabulary:
 *
 * - **Server-paged tables** (p-table `[lazy]`) cannot sort in the browser - the page on screen is
 *   a slice of a list only the server has in full. Their `onLazyLoad` event carries the clicked
 *   column, and {@link toSortParam} turns it into Spring Data's `sort=field,dir`.
 * - **Client-side tables and trees** hold every row already. PrimeNG would sort them itself, but
 *   only by a property path, and most interesting columns here are computed (a per-month total, a
 *   converted value, a category list). So they use `[customSort]` and hand the arrays to
 *   {@link sortInPlace} with a key function per column.
 *
 * Either way the header is PrimeNG's own `pSortableColumn` / `ttSortableColumn` with its sort icon,
 * so clicking a column name sorts by it, clicking again reverses it, and the arrow says which way.
 */

/** What PrimeNG passes to `onLazyLoad`, typed structurally (TableLazyLoadEvent is not exported from 'primeng/table'). */
export interface LazyTableEvent {
  first?: number | null;
  rows?: number | null;
  sortField?: string | string[] | null;
  sortOrder?: number | null;
}

/** What PrimeNG's `(sortFunction)` emits when `[customSort]` is on, for p-table and p-treetable alike. */
export interface SortFunctionEvent<T = unknown> {
  data?: T[];
  field?: string;
  order?: number;
}

/**
 * Turns a lazy-load event into a Spring Data `sort` parameter.
 *
 * @param columns maps the column key used in the template to the backend property path, e.g.
 *     `{ instrument: 'instrument.symbol' }`. A key not listed is passed through unchanged; only
 *     columns the template marks sortable can arrive here, so nothing arbitrary reaches the server.
 * @param fallback used before any column has been clicked, e.g. `'paymentDate,desc'`
 */
export function toSortParam(
  event: LazyTableEvent,
  columns: Record<string, string>,
  fallback: string,
): string {
  const field = typeof event.sortField === 'string' ? event.sortField : null;
  if (!field) {
    return fallback;
  }
  const property = columns[field] ?? field;
  return `${property},${event.sortOrder === -1 ? 'desc' : 'asc'}`;
}

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });

/**
 * Orders two cell values the way a person expects: text by locale with numbers inside it compared
 * numerically ("A2" before "A10"), numbers and dates by value, booleans true first.
 *
 * Missing values (null, undefined, NaN) always go **last, in both directions** - a column sorted
 * descending should still open with real figures, not with a block of em dashes.
 */
export function compareCells(a: unknown, b: unknown, order: 1 | -1): number {
  const aMissing = a === null || a === undefined || (typeof a === 'number' && Number.isNaN(a));
  const bMissing = b === null || b === undefined || (typeof b === 'number' && Number.isNaN(b));
  if (aMissing || bMissing) {
    return aMissing === bMissing ? 0 : aMissing ? 1 : -1;
  }
  let result: number;
  if (typeof a === 'number' && typeof b === 'number') {
    result = a - b;
  } else if (typeof a === 'boolean' && typeof b === 'boolean') {
    result = a === b ? 0 : a ? -1 : 1;
  } else {
    result = collator.compare(String(a), String(b));
  }
  return result * order;
}

/**
 * Sorts an array in place by a key function. In place on purpose: PrimeNG's custom sort hands over
 * the very array it renders (for a tree, one sibling list at a time) and expects it reordered.
 * `Array.prototype.sort` is stable, so rows with equal keys keep their previous relative order.
 */
export function sortInPlace<T>(items: T[], key: (item: T) => unknown, order: number | undefined): void {
  const direction: 1 | -1 = order === -1 ? -1 : 1;
  items.sort((left, right) => compareCells(key(left), key(right), direction));
}
