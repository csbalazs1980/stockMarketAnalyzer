package com.stockmarketanalyzer.backend.wealth.ratefeed;

/**
 * The upstream rate feed could not be used: unreachable, too slow, an error status, or an answer
 * that did not contain a usable quote.
 *
 * <p>Deliberately its own type rather than an {@code IllegalArgumentException}: this is not the
 * caller's fault and must not read as a 400. {@code GlobalExceptionHandler} maps it to a 502.
 */
public class RateFeedException extends RuntimeException {

    public RateFeedException(String message) {
        super(message);
    }

    public RateFeedException(String message, Throwable cause) {
        super(message, cause);
    }
}
