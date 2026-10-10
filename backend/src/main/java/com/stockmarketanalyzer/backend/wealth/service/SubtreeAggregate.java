package com.stockmarketanalyzer.backend.wealth.service;

import java.math.BigDecimal;

/**
 * Accumulator used while folding a subtree bottom-up.
 *
 * <p>{@code soleUnit} and {@code mixed} together answer "is this whole subtree denominated in
 * one unit?" without carrying a set of strings up the tree: {@code soleUnit} is the only unit
 * seen so far, and {@code mixed} latches once a second one turns up.
 */
record SubtreeAggregate(
        BigDecimal value, boolean partial, BigDecimal quantity, String soleUnit, boolean mixed) {

    static final SubtreeAggregate EMPTY = new SubtreeAggregate(null, false, null, null, false);
}
