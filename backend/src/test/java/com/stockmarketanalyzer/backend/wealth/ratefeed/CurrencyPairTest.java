package com.stockmarketanalyzer.backend.wealth.ratefeed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pair parsing is where a typo in {@code application.yaml} gets caught. It runs when the pairs are
 * read rather than when the button is pressed, so the failure is a complaint about that line
 * instead of a 502 that makes it look as though the feed is down.
 */
class CurrencyPairTest {

    @Test
    void parsesAndNormalisesAPair() {
        CurrencyPair pair = CurrencyPair.parse(" usd : huf ");

        assertThat(pair.base()).isEqualTo("USD");
        assertThat(pair.quote()).isEqualTo("HUF");
        assertThat(pair).hasToString("USD:HUF");
    }

    @Test
    void refusesAnythingThatIsNotOneBaseAndOneQuote() {
        assertThatThrownBy(() -> CurrencyPair.parse("USD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BASE:QUOTE");
        assertThatThrownBy(() -> CurrencyPair.parse("USD:HUF:EUR")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CurrencyPair.parse("USD:")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CurrencyPair.parse(null)).isInstanceOf(IllegalArgumentException.class);
        // The store would refuse this one anyway; catching it at startup says where it came from.
        assertThatThrownBy(() -> CurrencyPair.parse("USD:usd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("into itself");
    }
}
