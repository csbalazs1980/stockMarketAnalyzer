package com.stockmarketanalyzer.backend.common.csv;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Builds a CSV file, in exactly the dialect {@link CsvFile} reads back.
 *
 * <p>That symmetry is the whole point: an export is the owner's own rows written out in the shape
 * the importer expects, so a downloaded file can be edited in a spreadsheet and uploaded again, or
 * carried to another account, without anything in between having to know the format. Which is also
 * why the dialect is fixed rather than configurable -- commas, dots for decimals, year-first dates,
 * one header row. The reader accepts the Hungarian convention as well; writing it would only give
 * the round trip a second way to go wrong.
 *
 * <p>Line endings are CRLF (what RFC 4180 asks for and what Excel is happiest with) and numbers are
 * written in plain notation with trailing zeros stripped, so a rate stored as {@code 352.400000}
 * comes back out as {@code 352.4} rather than as a scientific-notation surprise.
 *
 * <p><strong>Nothing is prefixed to defend Excel against formulas.</strong> A cell starting with
 * {@code =} is a real concern when a file is exported from data other people typed; here every row
 * is the owner's own, going back to the owner, and the usual mitigation -- an apostrophe in front
 * -- would corrupt the value on the way back in, breaking the round trip this class exists for.
 */
public final class CsvWriter {

    /** The dialect {@link CsvFile} sniffs a comma-delimited file into. */
    private static final char DELIMITER = ',';

    private static final String LINE_END = "\r\n";

    private final StringBuilder out = new StringBuilder();
    private final int columnCount;

    /**
     * Starts a file with its header row.
     *
     * @param columns the column names, spelled as the matching importer's {@code CSV_COLUMNS}
     */
    public CsvWriter(List<String> columns) {
        this.columnCount = columns.size();
        append(columns.toArray());
    }

    /**
     * Writes one data row.
     *
     * <p>{@code null} becomes an empty cell, which is how every importer here spells "not given".
     * {@link BigDecimal}, {@link LocalDate} and enum values are written the way the reader parses
     * them; anything else is its {@code toString()}.
     *
     * @throws IllegalArgumentException when the row does not have one value per header column --
     *     a column added to the header and forgotten in a row would otherwise shift every cell
     *     after it into the wrong column
     */
    public CsvWriter row(Object... values) {
        if (values.length != columnCount) {
            throw new IllegalArgumentException(
                    "row has %d values but the header has %d columns".formatted(values.length, columnCount));
        }
        append(values);
        return this;
    }

    /** The file so far. A file with no data rows is its header row, not an empty string. */
    public String toCsv() {
        return out.toString();
    }

    private void append(Object[] values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                out.append(DELIMITER);
            }
            out.append(escape(text(values[index])));
        }
        out.append(LINE_END);
    }

    /** How each type is spelled on the way out. Mirrors what {@code CsvValues} reads on the way in. */
    static String text(Object value) {
        return switch (value) {
            case null -> "";
            case BigDecimal decimal -> decimal.stripTrailingZeros().toPlainString();
            case LocalDate date -> date.toString();
            case Enum<?> constant -> constant.name();
            default -> String.valueOf(value);
        };
    }

    /**
     * Quotes a field when it would otherwise be misread: it carries the delimiter, a quote or a
     * line break, or it has whitespace at either end that a reader trimming fields would eat.
     * Quotes inside a quoted field are doubled, as RFC 4180 says.
     */
    static String escape(String field) {
        boolean needsQuotes = field.indexOf(DELIMITER) >= 0
                || field.indexOf('"') >= 0
                || field.indexOf('\n') >= 0
                || field.indexOf('\r') >= 0
                || (!field.isEmpty() && (Character.isWhitespace(field.charAt(0))
                        || Character.isWhitespace(field.charAt(field.length() - 1))));
        if (!needsQuotes) {
            return field;
        }
        return '"' + field.replace("\"", "\"\"") + '"';
    }
}
