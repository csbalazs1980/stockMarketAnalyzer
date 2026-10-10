package com.stockmarketanalyzer.backend.dividend.service;

import java.math.BigDecimal;

/**
 * What a filter adds up to.
 *
 * @param count how many rows matched, converted or not
 * @param total the sum of the convertible ones, or {@code null} when none could be converted
 * @param partial true when at least one matching row had no rate, so {@code total} is a lower
 *     bound rather than the whole answer -- the same honesty rule as the wealth roll-up
 * @param notInflationAdjusted with the inflation adjustment on, how many rows had a figure left
 *     unadjusted for want of inflation data; always 0 otherwise
 */
public record DividendTotal(long count, BigDecimal total, boolean partial, long notInflationAdjusted) {

    public static final DividendTotal EMPTY = new DividendTotal(0L, null, false, 0L);

    public DividendTotal(long count, BigDecimal total, boolean partial) {
        this(count, total, partial, 0L);
    }
}
