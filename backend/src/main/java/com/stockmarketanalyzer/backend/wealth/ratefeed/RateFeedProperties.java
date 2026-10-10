package com.stockmarketanalyzer.backend.wealth.ratefeed;

import java.time.Duration;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from the {@code app.rate-feed.*} keys in application.yaml.
 *
 * <p>The defaults point at <a href="https://frankfurter.dev">Frankfurter</a>, which needs no API
 * key and has no quota, pinned to the {@code MNB} provider -- the Hungarian central bank's
 * official rate. Pinning matters twice over: an unpinned request lets the aggregator choose the
 * source, so the same pair on the same day can come back as a different number; and as of
 * 2026-09-08 the aggregator's {@code ECB} feed was roughly four weeks stale while {@code MNB} was
 * current, so the provider is not an incidental detail.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.rate-feed")
public class RateFeedProperties {

    /** Turn the refresh endpoint off entirely (it then answers 503). */
    private boolean enabled = true;

    /** Base URL of the feed's v2 API, without a trailing slash. */
    private String baseUrl = "https://api.frankfurter.dev/v2";

    /** Provider key to pin the numbers to. Empty lets the aggregator choose. */
    private String provider = "MNB";

    /**
     * Pairs to fetch, each written {@code BASE:QUOTE}.
     *
     * <p>{@code EUR:HUF} is here even though it looks derivable from the other two, because
     * valuation resolves a factor in three steps -- identity, a direct quote, the reciprocal of
     * the opposite quote -- and <em>does not chain</em>. Without a direct quote, anything held in
     * EUR is simply unconvertible.
     */
    private List<String> pairs = List.of("USD:HUF", "EUR:HUF", "EUR:USD");

    private Duration connectTimeout = Duration.ofSeconds(5);

    private Duration readTimeout = Duration.ofSeconds(10);

    /** The configured pairs, parsed and validated. Throws on a malformed entry. */
    public List<CurrencyPair> parsedPairs() {
        return pairs.stream().map(CurrencyPair::parse).toList();
    }
}
