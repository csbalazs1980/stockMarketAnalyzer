package com.stockmarketanalyzer.backend.wealth.service;

import java.math.BigDecimal;

/**
 * Everything derived on read for one wealth entry: what it is worth on its own, and what its
 * whole subtree is worth.
 *
 * @param ownValue      the entry's own quantity converted into the target unit, or {@code null}
 *                      when it holds nothing (an aggregate) or no rate connects the units
 * @param totalValue    {@code ownValue} plus every descendant's own value; {@code null} when
 *                      nothing in the subtree could be valued at all
 * @param totalPartial  true when at least one row in the subtree holds something the owner has
 *                      never quoted a rate for, so {@code totalValue} is an undercount rather
 *                      than a complete answer
 * @param totalQuantity {@code quantity} plus every descendant's quantity, but only when the
 *                      whole subtree is denominated in this entry's unit; {@code null} when the
 *                      subtree mixes units, because adding USD to OZ is meaningless
 */
public record EntryValuation(
        BigDecimal ownValue, BigDecimal totalValue, boolean totalPartial, BigDecimal totalQuantity) {

    public static final EntryValuation EMPTY = new EntryValuation(null, null, false, null);
}
