package com.stockmarketanalyzer.backend.common.csv;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Turns the text in a cell into the types the importers want, tolerantly enough that a file a
 * spreadsheet wrote is usable without being cleaned up by hand first.
 *
 * <p>The awkward part is that a Hungarian Excel writes {@code 1 234,56} where an English one
 * writes {@code 1,234.56}, and the same two characters mean opposite things in the two files.
 * {@link CsvFile} settles which is which from the delimiter it sniffed -- a semicolon-delimited
 * file is a comma-decimal file -- and passes the answer down here.
 */
final class CsvValues {

    private CsvValues() {}

    /**
     * Parses a number written with either separator convention.
     *
     * <p>Whitespace (including the non-breaking spaces spreadsheets like to group with) is
     * dropped first. After that the configured decimal separator always means a decimal point
     * and the other character always means thousands grouping -- except when only the other
     * character appears and the groups it produces are not three digits long, which is what
     * {@code 1234.56} in a semicolon file looks like. Reading that as 123456 would be a silent
     * hundred-fold error, so the group length decides.
     *
     * @return the parsed value, or {@code null} when the cell was blank
     * @throws NumberFormatException when the text is not a number under either reading
     */
    static BigDecimal decimal(String raw, char decimalSeparator) {
        String value = raw.replaceAll("[\\s\\u00A0\\u202F']", "");
        if (value.isEmpty()) {
            return null;
        }
        char grouping = decimalSeparator == '.' ? ',' : '.';
        if (value.indexOf(decimalSeparator) >= 0) {
            value = value.replace(String.valueOf(grouping), "").replace(decimalSeparator, '.');
        } else if (value.indexOf(grouping) >= 0) {
            value = groupedOrDecimal(value, grouping);
        }
        return new BigDecimal(value);
    }

    /** The only-the-other-separator case: three-digit groups are grouping, anything else is not. */
    private static String groupedOrDecimal(String value, char separator) {
        String[] parts = value.split(java.util.regex.Pattern.quote(String.valueOf(separator)), -1);
        boolean grouped = true;
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].length() != 3) {
                grouped = false;
                break;
            }
        }
        if (grouped) {
            return String.join("", parts);
        }
        if (parts.length != 2) {
            throw new NumberFormatException("cannot tell grouping from decimals in '%s'".formatted(value));
        }
        return parts[0] + "." + parts[1];
    }

    /**
     * Parses a date written year-first: {@code 2026-08-15}, {@code 2026.08.15} and
     * {@code 2026. 8. 15.} (what a Hungarian locale produces) all work, and so does a
     * {@code /} in place of the separator.
     *
     * <p>Day-first and month-first forms are refused rather than guessed at. {@code 05/08/2026}
     * is two different days depending on who wrote it, and quietly picking one would file a
     * payment in the wrong month.
     *
     * @return the parsed date, or {@code null} when the cell was blank
     * @throws IllegalArgumentException when the text is not a year-first date
     */
    static LocalDate date(String raw) {
        String value = raw.replaceAll("[\\s\\u00A0\\u202F]", "");
        if (value.isEmpty()) {
            return null;
        }
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        String[] parts = value.split("[-./]", -1);
        if (parts.length != 3 || parts[0].length() != 4) {
            throw new IllegalArgumentException(
                    "'%s' is not a year-first date such as 2026-08-15".formatted(raw.trim()));
        }
        try {
            return LocalDate.of(
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException | java.time.DateTimeException ex) {
            throw new IllegalArgumentException("'%s' is not a valid date".formatted(raw.trim()), ex);
        }
    }
}
