package com.stockmarketanalyzer.backend.inflation.service;

import com.stockmarketanalyzer.backend.common.Units;
import com.stockmarketanalyzer.backend.inflation.domain.InflationRate;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cumulative inflation factors, from a snapshot of the stored rates.
 *
 * <p>The rule (the user's, 2026-10-10): an amount in currency C received in year Y is multiplied by
 * {@code (1 + r[Y]/100) x ... x (1 + r[L]/100)}, where L is the latest year C has a figure for. The
 * payment year's own inflation is included whatever the month.
 *
 * <p><strong>Mirrors {@code features/inflation/inflation-adjust.ts} on the frontend</strong>, which
 * the monthly grid uses; the received-dividends table needs the server to do it because it pages,
 * sorts and totals there. The two must give the same numbers -- change both or neither:
 *
 * <ul>
 *   <li>no figures for the currency, or a year missing anywhere from Y to L: {@code null} (the
 *       caller leaves the figure unadjusted and says so -- a skipped hole would quietly understate
 *       the adjustment);
 *   <li>Y after L (paid before that year's figure is published): {@code 1}, the empty product.
 * </ul>
 *
 * <p>Immutable and cheap: the table is a few currencies times a few decades, read once per request.
 * Factors are memoised per (currency, year).
 */
public final class InflationFactors {

    /** Plenty for compounding a few dozen percentages; the result is rounded where it is used. */
    private static final MathContext PRECISION = MathContext.DECIMAL64;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final Map<String, TreeMap<Integer, BigDecimal>> ratesByUnit;
    private final Map<String, BigDecimal> memo = new HashMap<>();

    private InflationFactors(Map<String, TreeMap<Integer, BigDecimal>> ratesByUnit) {
        this.ratesByUnit = ratesByUnit;
    }

    public static InflationFactors of(Collection<InflationRate> rates) {
        Map<String, TreeMap<Integer, BigDecimal>> byUnit = new HashMap<>();
        for (InflationRate rate : rates) {
            byUnit.computeIfAbsent(rate.getUnit(), unit -> new TreeMap<>()).put(rate.getYear(), rate.getRate());
        }
        return new InflationFactors(byUnit);
    }

    /** The latest year the currency has a figure for, or {@code null}. */
    public Integer latestYear(String unit) {
        TreeMap<Integer, BigDecimal> years = ratesByUnit.get(Units.normalise(unit));
        return years == null || years.isEmpty() ? null : years.lastKey();
    }

    /**
     * The multiplier for an amount in {@code unit} received in {@code year}, or {@code null} when it
     * cannot be computed (see the class comment).
     */
    public BigDecimal factor(String unit, int year) {
        String normalised = Units.normalise(unit);
        String key = normalised + "/" + year;
        if (memo.containsKey(key)) {
            return memo.get(key);
        }
        BigDecimal factor = compute(normalised, year);
        memo.put(key, factor);
        return factor;
    }

    private BigDecimal compute(String unit, int year) {
        TreeMap<Integer, BigDecimal> years = ratesByUnit.get(unit);
        if (years == null || years.isEmpty()) {
            return null;
        }
        BigDecimal factor = BigDecimal.ONE;
        for (int y = year; y <= years.lastKey(); y++) {
            BigDecimal rate = years.get(y);
            if (rate == null) {
                return null;
            }
            factor = factor.multiply(BigDecimal.ONE.add(rate.divide(HUNDRED, PRECISION)), PRECISION);
        }
        return factor;
    }
}
