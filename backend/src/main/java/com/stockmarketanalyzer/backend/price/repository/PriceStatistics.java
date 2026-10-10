package com.stockmarketanalyzer.backend.price.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Aggregate result of {@link PriceBarRepository#statistics}.
 */
public record PriceStatistics(
        long barCount,
        BigDecimal lowest,
        BigDecimal highest,
        Double averageClose,
        LocalDate firstTradeDate,
        LocalDate lastTradeDate) {}
