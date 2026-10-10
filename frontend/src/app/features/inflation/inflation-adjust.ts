/**
 * Cumulative inflation adjustment: expressing an old amount in the money of the latest year that
 * has an inflation figure.
 *
 * The rule (the user's, 2026-10-10): a payment received in year Y is multiplied by
 *
 *     (1 + r[Y]/100) x (1 + r[Y+1]/100) x ... x (1 + r[L]/100)
 *
 * where r is the inflation of the payment's currency and L is the latest year that currency has a
 * figure for. The payment year's own inflation is included, whatever month the payment came in.
 *
 * Pure: no Angular, no HTTP. The page hands it the stored rates and the payments.
 */

import { InflationRate } from './inflation.models';

/** One currency's figures, year -> percentage points. */
export type InflationIndex = Map<string, Map<number, number>>;

export function buildInflationIndex(rates: InflationRate[]): InflationIndex {
  const index: InflationIndex = new Map();
  for (const rate of rates) {
    const unit = rate.unit.toUpperCase();
    let years = index.get(unit);
    if (!years) {
      years = new Map();
      index.set(unit, years);
    }
    years.set(rate.year, rate.rate);
  }
  return index;
}

/** The latest year a currency has a figure for, or null when it has none. */
export function latestInflationYear(index: InflationIndex, unit: string): number | null {
  const years = index.get(unit.toUpperCase());
  if (!years || years.size === 0) {
    return null;
  }
  return Math.max(...years.keys());
}

/**
 * The multiplier for an amount in `unit` received in `year`, or null when it cannot be computed.
 *
 * - No figures for the currency at all: null.
 * - A year missing between `year` and the latest one (including `year` itself, e.g. a payment
 *   older than the series): null. Skipping a hole would quietly understate the adjustment.
 * - `year` after the latest figure (a payment this year, before its inflation is published):
 *   1 - there is nothing to apply yet, which is what the rule's empty product gives.
 */
export function cumulativeFactor(index: InflationIndex, unit: string, year: number): number | null {
  const years = index.get(unit.toUpperCase());
  const latest = latestInflationYear(index, unit);
  if (!years || latest === null) {
    return null;
  }
  let factor = 1;
  for (let y = year; y <= latest; y += 1) {
    const rate = years.get(y);
    if (rate === undefined) {
      return null;
    }
    factor *= 1 + rate / 100;
  }
  return factor;
}

/** The fields of a payment the adjustment touches. Dividend satisfies it structurally. */
export interface AdjustablePayment {
  amount: number;
  unit: string;
  /** ISO date; only the year is read. */
  paymentDate: string;
  currentValue: number | null;
  valueUnit: string;
}

export interface AdjustmentResult<T> {
  rows: T[];
  /** Currencies whose payments were left as they are, because no factor could be computed. */
  unadjustedUnits: string[];
  /** How many amounts, and how many converted values, were left as they are. */
  unadjustedAmounts: number;
  unadjustedValues: number;
}

/**
 * Adjusts every payment, on both of its figures independently:
 *
 * - `amount` with the inflation of the currency it was **received** in (20 USD in 2023 -> USD
 *   inflation 2023 onwards);
 * - `currentValue` - the amount already converted at the payment date's rate - with the inflation
 *   of the **value** currency (6000 HUF -> HUF inflation 2023 onwards).
 *
 * The conversion is never redone: the value is converted first and adjusted afterwards, which is
 * what the user asked for and also the only order that keeps using the historical rate.
 *
 * A figure that cannot be adjusted is left exactly as it was and counted, never zeroed or dropped;
 * the caller says so on screen.
 */
export function adjustPayments<T extends AdjustablePayment>(
  payments: T[],
  index: InflationIndex,
): AdjustmentResult<T> {
  const missing = new Set<string>();
  let unadjustedAmounts = 0;
  let unadjustedValues = 0;

  const rows = payments.map((payment) => {
    const year = Number(payment.paymentDate.slice(0, 4));

    const amountFactor = cumulativeFactor(index, payment.unit, year);
    if (amountFactor === null) {
      missing.add(payment.unit.toUpperCase());
      unadjustedAmounts += 1;
    }

    let currentValue = payment.currentValue;
    if (currentValue !== null) {
      const valueFactor = cumulativeFactor(index, payment.valueUnit, year);
      if (valueFactor === null) {
        missing.add(payment.valueUnit.toUpperCase());
        unadjustedValues += 1;
      } else {
        currentValue *= valueFactor;
      }
    }

    return {
      ...payment,
      amount: amountFactor === null ? payment.amount : payment.amount * amountFactor,
      currentValue,
    };
  });

  return { rows, unadjustedUnits: [...missing].sort(), unadjustedAmounts, unadjustedValues };
}
