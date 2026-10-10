package com.stockmarketanalyzer.backend.instrument.web.dto;

import com.stockmarketanalyzer.backend.instrument.repository.InstrumentOptionView;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The slice of an instrument a picker needs: enough to label an option, identify it on submit,
 * and prefill a currency. Deliberately not {@link InstrumentResponse} -- this endpoint answers
 * with the whole universe at once, so every field carried is paid for a few thousand times.
 */
@Schema(name = "InstrumentOption", description = "An instrument reduced to what a picker needs.")
public record InstrumentOption(Long id, String symbol, String name, String currency) {

    public static InstrumentOption from(InstrumentOptionView view) {
        return new InstrumentOption(view.getId(), view.getSymbol(), view.getName(), view.getCurrency());
    }
}
