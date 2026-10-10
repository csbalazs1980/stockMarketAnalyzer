package com.stockmarketanalyzer.backend.wealth.ratefeed;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row as the feed returns it: {@code {"date":"2026-09-08","base":"USD","quote":"HUF",
 * "rate":314.55}}.
 *
 * <p>The shape is nearly the application's own {@code ExchangeRateRequest}, which is why this
 * integration is as small as it is -- {@code base}/{@code quote}/{@code rate}/{@code date} map
 * straight onto {@code sourceUnit}/{@code destinationUnit}/{@code rate}/{@code rateDate}.
 *
 * <p><strong>{@code date} is the publication date, not today.</strong> Asked on a Sunday the feed
 * answers with Friday's rate, and storing that under Sunday would record a quote that was never
 * published. Stamping it correctly is also what makes the button idempotent: the store is an
 * upsert on (pair, date), so pressing it three times over a weekend rewrites one row instead of
 * inventing three.
 */
public record RateQuote(LocalDate date, String base, String quote, BigDecimal rate) {}
