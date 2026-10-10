package com.stockmarketanalyzer.backend.wealth.ratefeed;

import com.stockmarketanalyzer.backend.common.Units;

/**
 * One quote to ask the feed for, written {@code USD:HUF} in configuration.
 *
 * <p>Parsing is strict and happens when the pairs are read rather than when the button is
 * pressed: a typo in {@code application.yaml} should be a startup-time complaint about that
 * line, not a 502 that looks like the feed is down.
 */
public record CurrencyPair(String base, String quote) {

    public static CurrencyPair parse(String value) {
        String[] parts = value == null ? new String[0] : value.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "Rate feed pair '%s' must be written BASE:QUOTE, for example USD:HUF".formatted(value));
        }
        String base = Units.normalise(parts[0]);
        String quote = Units.normalise(parts[1]);
        if (base.isEmpty() || quote.isEmpty()) {
            throw new IllegalArgumentException("Rate feed pair '%s' has an empty side".formatted(value));
        }
        if (base.equals(quote)) {
            throw new IllegalArgumentException(
                    "Rate feed pair '%s' converts a unit into itself".formatted(value));
        }
        return new CurrencyPair(base, quote);
    }

    @Override
    public String toString() {
        return base + ":" + quote;
    }
}
