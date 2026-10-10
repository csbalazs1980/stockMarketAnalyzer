package com.stockmarketanalyzer.backend.inflation.feed;

/**
 * The upstream inflation source could not be used: unreachable, too slow, an error status, or an
 * answer that was not the shape expected. {@code GlobalExceptionHandler} maps it to a 502 -- not
 * the caller's fault, and nothing wrong with this application either.
 */
public class InflationFeedException extends RuntimeException {

    public InflationFeedException(String message) {
        super(message);
    }

    public InflationFeedException(String message, Throwable cause) {
        super(message, cause);
    }
}
