/**
 * Mirrors the backend DTOs in com.stockmarketanalyzer.backend.expense.web.dto.
 *
 * As with wealth.models.ts and dividend.models.ts, this is hand-written until the generated client
 * lands:
 *   npx @openapitools/openapi-generator-cli generate \
 *     -i http://localhost:8080/v3/api-docs/v1 -g typescript-angular -o src/app/api
 */

/**
 * The period half of a recurrence. The interval is the other half: 2 + WEEKLY is fortnightly,
 * 6 + MONTHLY is twice a year - the same shape iCalendar's RRULE uses (FREQ plus INTERVAL).
 *
 * There is deliberately no QUARTERLY: it would make "3 MONTHLY" a second spelling of one thing,
 * and then every sum has to know the two are equal. Quarterly is a preset in the form instead.
 */
export type RecurrencePeriod = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY';

export const RECURRENCE_PERIODS: readonly RecurrencePeriod[] = ['DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'];

/** A tag, either the signed-in user's own or a global one shared by the installation. */
export interface ExpenseCategory {
  id: number;
  category: string;
  /** Global rows are visible to everybody and editable only by an administrator. */
  global: boolean;
}

export interface ExpenseCategoryRequest {
  category: string;
  /** Asking for true without the ADMIN role is a 403, not a silently personal tag. */
  global: boolean;
}

export interface ExpenseCategoryQuery {
  search?: string | null;
  /** Omit for both kinds, true for global only, false for your own only. */
  global?: boolean | null;
  page?: number;
  size?: number;
}

export interface ExpenseItem {
  id: number;
  name: string;
  /** What one occurrence costs, as entered. */
  amount: number;
  /** The currency it is paid in. Nothing is converted - see expense-recurrence.ts. */
  unit: string;
  /** How many periods between charges. */
  recurrenceInterval: number;
  recurrencePeriod: RecurrencePeriod;
  /** ISO-8601, or null for "this is simply what I pay", with no calendar attached. */
  startDate: string | null;
  /** ISO-8601, or null for the ordinary case: it has not got an end. */
  expiryDate: string | null;
  /** Tags, personal and global together, alphabetically. */
  categories: ExpenseCategory[];
  /** Charged today: started on or before today and not expired. Computed by the backend. */
  active: boolean;
}

export interface ExpenseItemRequest {
  name: string;
  amount: number;
  unit: string;
  recurrenceInterval: number;
  recurrencePeriod: RecurrencePeriod;
  startDate?: string | null;
  expiryDate?: string | null;
  /** The complete tag set afterwards, not a delta. */
  categoryIds: number[];
}

export interface ExpenseItemQuery {
  search?: string | null;
  unit?: string | null;
  period?: RecurrencePeriod | null;
  categoryId?: number | null;
  /** True hides rows that have not started yet or have already expired. */
  activeOnly?: boolean | null;
  page?: number;
  size?: number;
}

/** Which of the two tables the items page is showing. */
export type ExpenseView = 'list' | 'categories';
