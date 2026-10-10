package com.stockmarketanalyzer.backend.instrument.repository;

/**
 * Closed projection over {@code instrument} for pickers: the four fields a dropdown needs and
 * nothing else. Spring Data derives {@code select i.id, i.symbol, i.name, i.currency} from the
 * getters, so the unpaged options query never materialises entities -- which matters once the
 * universe is measured in thousands of rows.
 */
public interface InstrumentOptionView {

    Long getId();

    String getSymbol();

    String getName();

    String getCurrency();
}
