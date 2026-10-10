/**
 * Mirrors the backend DTOs in com.stockmarketanalyzer.backend.wealth.web.dto.
 *
 * Once the backend is running you can replace this file with a generated client:
 *   npx @openapitools/openapi-generator-cli generate \
 *     -i http://localhost:8080/v3/api-docs/v1 -g typescript-angular -o src/app/api
 */

export interface WealthEntry {
  id: number;
  name: string;
  unit: string;
  /** null marks an aggregate row that only rolls its children up - not a holding of zero. */
  quantity: number | null;
  /** ISO-8601 date, e.g. 2026-08-05. */
  entryDate: string;
  parentId: number | null;
  /** This row's own quantity valued. null when it holds nothing, or no rate connects the units. */
  currentValue: number | null;
  /** currentValue plus every descendant's. The number to show against an aggregate row. */
  totalValue: number | null;
  /** True when something in the subtree had no rate, so totalValue is a lower bound. */
  totalValuePartial: boolean;
  /** quantity plus every descendant's - but only when the subtree is all in this row's unit. */
  totalQuantity: number | null;
  valueUnit: string;
}

export interface WealthEntryRequest {
  name: string;
  unit: string;
  /** Omit (or null) for an aggregate row. */
  quantity?: number | null;
  entryDate?: string | null;
  parentId?: number | null;
}

/** One row of a clone, as the form left it. Absent rows are copied exactly as they stand. */
export interface WealthEntryCloneOverride {
  sourceId: number;
  /** null is meaningful: it records the copy as an aggregate row. */
  quantity: number | null;
  /** null or blank inherits the unit of the copy above it. */
  unit: string | null;
}

export interface WealthEntryCloneRequest {
  /** Applies to every copied row - a clone is one snapshot, not a mix of dates. */
  entryDate?: string | null;
  overrides?: WealthEntryCloneOverride[];
}

export interface WealthEntryQuery {
  from?: string | null;
  to?: string | null;
  unit?: string | null;
  parentId?: number | null;
  rootsOnly?: boolean;
  search?: string | null;
  valueIn?: string | null;
  page?: number;
  size?: number;
}

export interface ExchangeRate {
  id: number;
  sourceUnit: string;
  destinationUnit: string;
  rate: number;
  rateDate: string;
  /** Shared by every user and writable only by an administrator. A personal rate always wins. */
  global: boolean;
}

export interface ExchangeRateRequest {
  sourceUnit: string;
  destinationUnit: string;
  rate: number;
  rateDate?: string | null;
  /** Asking for true without the ADMIN role is a 403, not a silently personal rate. */
  global?: boolean;
}

/** What one press of the "fetch latest" button stored. Mirrors RateRefreshResponse. */
export interface RateRefreshResult {
  /** The source the numbers were pinned to, e.g. MNB. */
  provider: string;
  /** Pairs the feed was asked for. */
  requested: number;
  /** Pairs it answered with, and that were stored. */
  stored: number;
  /**
   * The stored quotes. Their rateDate is the feed's publication date, which on a weekend is the
   * previous working day - never blindly today.
   */
  rates: ExchangeRate[];
}

export interface ExchangeRateQuery {
  /** null for both kinds, true for global only, false for the caller's own only. */
  global?: boolean | null;
  sourceUnit?: string | null;
  destinationUnit?: string | null;
  from?: string | null;
  to?: string | null;
  page?: number;
  size?: number;
  /** Spring Data `field,dir`, e.g. `rateDate,desc`. */
  sort?: string;
}

export const DEFAULT_VALUE_UNIT = 'HUF';
