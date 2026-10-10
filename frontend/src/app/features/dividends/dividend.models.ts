/**
 * Mirrors the backend DTOs in com.stockmarketanalyzer.backend.dividend.web.dto.
 *
 * As with wealth.models.ts, this is hand-written until the generated client lands:
 *   npx @openapitools/openapi-generator-cli generate \
 *     -i http://localhost:8080/v3/api-docs/v1 -g typescript-angular -o src/app/api
 */

export interface Dividend {
  id: number;
  instrumentId: number;
  instrumentSymbol: string;
  instrumentName: string;
  /** What was received, as entered - before any conversion. */
  amount: number;
  /** The currency it actually landed in, which need not be what the instrument trades in. */
  unit: string;
  /** ISO-8601 date, e.g. 2026-08-15. */
  paymentDate: string;
  /** amount valued in valueUnit. null when no rate connects the units - never 0. */
  currentValue: number | null;
  valueUnit: string;
  /**
   * Only when the list was asked for `inflationAdjusted=true`. `amount` and `currentValue` above
   * always stay as stored, so an edit dialog fed from a row never saves an adjusted figure.
   */
  inflationAdjusted?: DividendInflationAdjusted | null;
}

/** Mirrors DividendResponse.InflationAdjusted. */
export interface DividendInflationAdjusted {
  /** amount x cumulative inflation of its own currency; the amount as entered when not computable. */
  amount: number;
  /** currentValue x cumulative inflation of valueUnit; null exactly when currentValue is. */
  currentValue: number | null;
  /** False when either figure was left unadjusted for want of inflation data. */
  complete: boolean;
}

export interface DividendRequest {
  instrumentId: number;
  amount: number;
  unit: string;
  paymentDate?: string | null;
}

export interface DividendQuery {
  from?: string | null;
  to?: string | null;
  instrumentId?: number | null;
  unit?: string | null;
  valueIn?: string | null;
  /** Adjust amounts and values for cumulative inflation (list and summary). */
  inflationAdjusted?: boolean | null;
  page?: number;
  size?: number;
  /** Spring Data `field,dir`, e.g. `paymentDate,desc`. Ignored by the summary. */
  sort?: string;
}

/** What the current filter adds up to - over every matching row, not just the page shown. */
export interface DividendSummary {
  count: number;
  /** null when nothing matched, or when none of the matches could be converted. */
  total: number | null;
  /** True when some matching row had no rate, so total is a lower bound. */
  totalPartial: boolean;
  valueUnit: string;
  /** Whether total is the inflation-adjusted sum. */
  inflationAdjusted?: boolean;
  /** With the adjustment on, rows that had a figure left unadjusted for want of inflation data. */
  notInflationAdjusted?: number;
}

export const DEFAULT_VALUE_UNIT = 'HUF';
