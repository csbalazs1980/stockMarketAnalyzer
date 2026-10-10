/**
 * Mirrors com.stockmarketanalyzer.backend.inflation.web.dto.*.
 *
 * Hand-written until the generated client lands, like instrument.models.ts.
 */

/**
 * Annual inflation for one currency, in percentage points: `4.6` means 4.6%, and a negative
 * value is deflation. Shared reference data - the same figure for every user.
 */
export interface InflationRate {
  id: number;
  unit: string;
  year: number;
  rate: number;
}

export interface InflationRateRequest {
  unit: string;
  year: number;
  rate: number;
}

export interface InflationRateQuery {
  unit?: string | null;
  fromYear?: number | null;
  toYear?: number | null;
  page?: number;
  size?: number;
  sort?: string;
}

/** The backend's own bounds on InflationRateRequest, checked here to save a round trip. */
export const INFLATION_YEAR_MIN = 1900;
export const INFLATION_YEAR_MAX = 2200;
export const INFLATION_RATE_MIN = -100;
export const INFLATION_RATE_MAX = 99999.9999;

/** Mirrors InflationFetchResponse.UnitResult - what one fetch did for one currency. */
export interface InflationFetchUnitResult {
  unit: string;
  geo: string;
  /** Latest year held before the fetch; null when the currency had none. */
  previousLatestYear: number | null;
  /** Latest year the source publishes; null when it publishes none. */
  latestAvailableYear: number | null;
  /** Rows added, oldest first. */
  stored: InflationRate[];
}

/** Mirrors InflationFetchResponse. */
export interface InflationFetchResponse {
  source: string;
  stored: number;
  units: InflationFetchUnitResult[];
}
