package com.stockmarketanalyzer.backend.common.csv;

/**
 * The uploaded file is not usable at all: it is not a CSV, it is empty, it has no header row,
 * or the header is missing a column the importer needs. Nothing was stored.
 *
 * <p>It extends {@link IllegalArgumentException} so {@code GlobalExceptionHandler} already turns
 * it into a 400 problem detail with the message below -- there is nothing slice-specific about
 * "this file cannot be read".
 */
public class CsvFormatException extends IllegalArgumentException {

    public CsvFormatException(String message) {
        super(message);
    }
}
