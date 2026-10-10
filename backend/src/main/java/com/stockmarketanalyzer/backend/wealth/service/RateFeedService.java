package com.stockmarketanalyzer.backend.wealth.service;

import com.stockmarketanalyzer.backend.wealth.ratefeed.CurrencyPair;
import com.stockmarketanalyzer.backend.wealth.ratefeed.RateFeedClient;
import com.stockmarketanalyzer.backend.wealth.ratefeed.RateFeedException;
import com.stockmarketanalyzer.backend.wealth.ratefeed.RateFeedProperties;
import com.stockmarketanalyzer.backend.wealth.ratefeed.RateQuote;
import com.stockmarketanalyzer.backend.wealth.web.dto.ExchangeRateResponse;
import com.stockmarketanalyzer.backend.wealth.web.dto.RateRefreshResponse;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Fetches published rates and records them as <em>global</em> rates (changed 2026-10-07; they used
 * to be stored as the caller's own marks).
 *
 * <p>It sits between {@link RateFeedClient} and {@link ExchangeRateService} rather than inside
 * either. The client must not know about owners, and the storage service must not acquire an HTTP
 * dependency -- {@code DividendService} and {@code WealthEntryService} both depend on it to value
 * their rows, and neither of them should transitively depend on the internet being up.
 *
 * <p><strong>A fetched rate is shared.</strong> The official central-bank rate is the same number
 * for everybody, so it is stored once, without an owner, and every user is valued from it unless
 * they have quoted the pair themselves (a personal rate always wins). Only an administrator may
 * press the button. {@link ExchangeRateService#storeGlobal} carries no caller check of its own, so
 * a future scheduled job -- which has no JWT and no caller -- can store the same rows.
 *
 * <p><strong>There is no transaction around the refresh.</strong> The network call happens with
 * no connection held, and each pair is then stored by the very same upsert the manual form uses.
 * The cost is that a failure part-way leaves the earlier pairs stored; because the store is an
 * upsert on (pair, date), pressing the button again simply completes the job, which is a better
 * trade than holding a database connection open across a call to somebody else's server.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateFeedService {

    private final RateFeedClient client;
    private final RateFeedProperties properties;
    private final ExchangeRateService exchangeRates;

    /**
     * Fetches the configured pairs and stores them as <em>global</em> rates, so one press updates
     * every user's valuations. Administrators only -- checked before the network call, so a refused
     * caller costs the feed nothing.
     */
    public RateRefreshResponse refresh() {
        exchangeRates.requireAdmin("fetch the shared exchange rates");
        if (!properties.isEnabled()) {
            throw new RateFeedException("The rate feed is disabled (app.rate-feed.enabled is false).");
        }

        List<CurrencyPair> pairs = properties.parsedPairs();
        List<RateQuote> quotes = client.fetch(pairs);

        List<ExchangeRateResponse> stored = new ArrayList<>();
        for (RateQuote quote : quotes) {
            stored.add(ExchangeRateResponse.from(
                    exchangeRates.storeGlobal(quote.base(), quote.quote(), quote.rate(), quote.date())));
        }

        log.info(
                "Stored {} of {} feed rates from {}",
                stored.size(),
                pairs.size(),
                properties.getProvider());
        return new RateRefreshResponse(properties.getProvider(), pairs.size(), stored.size(), stored);
    }
}
