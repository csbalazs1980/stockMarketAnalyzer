package com.stockmarketanalyzer.backend.common.csv;

/**
 * One data row could not be read -- a blank required cell, an unparseable number or date, a
 * symbol nobody has heard of. The file as a whole is fine, so the importer counts the row as
 * failed and carries on with the next one.
 *
 * <p>The line number is carried even though the API does not report it yet: it is what the
 * debug log prints, and it is what a future "which rows failed" response will need.
 */
public class CsvRowException extends RuntimeException {

    private final int lineNumber;

    public CsvRowException(int lineNumber, String message) {
        super("line %d: %s".formatted(lineNumber, message));
        this.lineNumber = lineNumber;
    }

    public int getLineNumber() {
        return lineNumber;
    }
}
