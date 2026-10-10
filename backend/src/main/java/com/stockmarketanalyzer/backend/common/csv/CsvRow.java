package com.stockmarketanalyzer.backend.common.csv;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * One data row, addressed by column name rather than position, so a file whose columns are in a
 * different order still imports.
 *
 * <p>Every accessor that cannot produce a value throws {@link CsvRowException}. That is the
 * whole error protocol: an importer wraps the row in one try/catch, counts the failure and moves
 * on, instead of threading a validation result through every field.
 *
 * @param lineNumber 1-based line in the uploaded file, for the log
 * @param values cell text keyed by {@link CsvFile#normaliseColumn normalised} column name
 * @param decimalSeparator which character means "decimal point" in this file
 */
public record CsvRow(int lineNumber, Map<String, String> values, char decimalSeparator) {

    /** The cell as written, or {@code null} when it is absent or blank. */
    public String text(String column) {
        String value = values.get(CsvFile.normaliseColumn(column));
        return value == null || value.isBlank() ? null : value.trim();
    }

    public String requireText(String column) {
        String value = text(column);
        if (value == null) {
            throw new CsvRowException(lineNumber, "'%s' is empty".formatted(column));
        }
        return value;
    }

    /** A number that must be there and must be greater than zero. */
    public BigDecimal requirePositive(String column) {
        BigDecimal value = requireDecimal(column);
        if (value.signum() <= 0) {
            throw new CsvRowException(lineNumber, "'%s' must be greater than zero".formatted(column));
        }
        return value;
    }

    /** A number that must be there and must not be negative. Zero is a legitimate amount. */
    public BigDecimal requireNonNegative(String column) {
        BigDecimal value = requireDecimal(column);
        if (value.signum() < 0) {
            throw new CsvRowException(lineNumber, "'%s' cannot be negative".formatted(column));
        }
        return value;
    }

    /**
     * A number that must be there and may be negative -- a figure such as an inflation rate,
     * where deflation is a legitimate value. A trailing {@code %} is dropped, because a
     * spreadsheet column formatted as a percentage writes {@code 4.6%} rather than {@code 4.6}.
     * The number is taken as written ({@code 4.6%} is 4.6, not 0.046).
     */
    public BigDecimal requirePercent(String column) {
        String raw = requireText(column);
        String number = raw.endsWith("%") ? raw.substring(0, raw.length() - 1) : raw;
        BigDecimal value;
        try {
            value = CsvValues.decimal(number, decimalSeparator);
        } catch (NumberFormatException ex) {
            throw new CsvRowException(lineNumber, "'%s' is not a number: '%s'".formatted(column, raw));
        }
        if (value == null) {
            throw new CsvRowException(lineNumber, "'%s' is empty".formatted(column));
        }
        return value;
    }

    /** A whole number that must be there, such as a year. Grouping spaces are ignored. */
    public int requireInteger(String column) {
        String raw = requireText(column);
        try {
            return Integer.parseInt(raw.replaceAll("[\\s\\u00A0\\u202F]", ""));
        } catch (NumberFormatException ex) {
            throw new CsvRowException(lineNumber, "'%s' is not a whole number: '%s'".formatted(column, raw));
        }
    }

    /** A date that may be left out: a blank cell means {@code fallback}, as the API does. */
    public LocalDate dateOr(String column, LocalDate fallback) {
        String raw = text(column);
        if (raw == null) {
            return fallback;
        }
        try {
            return CsvValues.date(raw);
        } catch (RuntimeException ex) {
            throw new CsvRowException(lineNumber, "'%s': %s".formatted(column, ex.getMessage()));
        }
    }

    private BigDecimal requireDecimal(String column) {
        String raw = requireText(column);
        BigDecimal value;
        try {
            value = CsvValues.decimal(raw, decimalSeparator);
        } catch (NumberFormatException ex) {
            throw new CsvRowException(lineNumber, "'%s' is not a number: '%s'".formatted(column, raw));
        }
        if (value == null) {
            throw new CsvRowException(lineNumber, "'%s' is empty".formatted(column));
        }
        return value;
    }
}
