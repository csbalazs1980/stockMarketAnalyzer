package com.stockmarketanalyzer.backend.common;

/**
 * Units of measure are free-form across the application -- USD, HUF, OZ, KG -- so they are
 * normalised in exactly one place. Lookups compare units as stored, and "kg" failing to match
 * "KG" would silently make a holding unconvertible.
 */
public final class Units {

    private Units() {}

    public static String normalise(String unit) {
        return unit == null ? null : unit.trim().toUpperCase();
    }
}
