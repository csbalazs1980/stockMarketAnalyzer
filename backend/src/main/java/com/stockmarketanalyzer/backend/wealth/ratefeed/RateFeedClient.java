package com.stockmarketanalyzer.backend.wealth.ratefeed;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * Reads published exchange rates from the upstream feed.
 *
 * <p>This is the only place in the application that talks to the internet. It knows nothing about
 * owners, storage or the application's own units: it takes pairs, returns quotes, and turns every
 * way the network can fail into one {@link RateFeedException}.
 */
@Slf4j
@Component
public class RateFeedClient {

    private final RestClient restClient;
    private final RateFeedProperties properties;

    public RateFeedClient(
            @Qualifier("rateFeedRestClient") RestClient restClient, RateFeedProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * Fetches every requested pair, or throws.
     *
     * <p>Requests are grouped by base currency because the feed takes one base and a list of
     * quotes, so USD:HUF, EUR:HUF and EUR:USD cost two round trips rather than three.
     *
     * <p>A base that fails fails the whole refresh. The alternative -- storing whatever arrived
     * and reporting the rest as missing -- would mean a button that half worked, and the caller
     * could not tell a currency the feed does not publish from one whose request timed out.
     */
    public List<RateQuote> fetch(Collection<CurrencyPair> pairs) {
        if (pairs.isEmpty()) {
            return List.of();
        }

        Map<String, Set<String>> quotesByBase = new LinkedHashMap<>();
        for (CurrencyPair pair : pairs) {
            quotesByBase.computeIfAbsent(pair.base(), key -> new LinkedHashSet<>()).add(pair.quote());
        }

        Set<CurrencyPair> wanted = new LinkedHashSet<>(pairs);
        List<RateQuote> quotes = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : quotesByBase.entrySet()) {
            for (RateQuote quote : request(entry.getKey(), entry.getValue())) {
                // The feed is a third party: only rows that were actually asked for are kept, and
                // an unusable row is a failure rather than a null rate stored as if it were real.
                if (quote.base() == null || quote.quote() == null) {
                    continue;
                }
                if (!wanted.contains(new CurrencyPair(quote.base(), quote.quote()))) {
                    continue;
                }
                if (quote.rate() == null || quote.rate().signum() <= 0 || quote.date() == null) {
                    throw new RateFeedException("The rate feed returned an unusable quote for %s:%s"
                            .formatted(quote.base(), quote.quote()));
                }
                quotes.add(quote);
            }
        }

        if (quotes.size() < wanted.size()) {
            log.warn("Rate feed returned {} of the {} pairs asked for", quotes.size(), wanted.size());
        }
        return quotes;
    }

    private List<RateQuote> request(String base, Set<String> quotes) {
        String joined = String.join(",", quotes);
        RateQuote[] body;
        try {
            body = restClient
                    .get()
                    .uri(uri -> buildUri(uri, base, joined))
                    .retrieve()
                    .body(RateQuote[].class);
        } catch (RestClientException ex) {
            throw new RateFeedException(
                    "Could not read %s rates from the rate feed: %s".formatted(base, ex.getMessage()), ex);
        }
        if (body == null) {
            throw new RateFeedException("The rate feed returned an empty response for " + base);
        }
        log.debug("Rate feed returned {} quote(s) for base {}", body.length, base);
        return List.of(body);
    }

    private URI buildUri(UriBuilder uri, String base, String quotes) {
        uri.path("/rates").queryParam("base", base).queryParam("quotes", quotes);
        String provider = properties.getProvider();
        if (provider != null && !provider.isBlank()) {
            uri.queryParam("providers", provider.trim());
        }
        return uri.build();
    }
}
