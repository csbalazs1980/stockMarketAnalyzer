package com.stockmarketanalyzer.backend.inflation.feed;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Map;
import java.util.SortedMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * Reads annual inflation series from Eurostat.
 *
 * <p>Knows nothing about storage or which years are already held: it takes a Eurostat country code
 * and returns that country's whole series. The series is a few dozen numbers, so asking for all of
 * it and letting the caller pick the new years is simpler and sturdier than a
 * {@code sinceTimePeriod} filter: there is no "nothing newer yet" edge case to get wrong, and a year
 * Eurostat revises later is at least seen (it is not stored -- see {@code InflationFeedService}).
 *
 * <p>One request per country rather than one with a repeated {@code geo} parameter, so that the
 * answer is always a single series and {@link EurostatDataset#yearly()} can insist on that.
 */
@Slf4j
@Component
public class InflationFeedClient {

    private final RestClient restClient;
    private final InflationFeedProperties properties;

    public InflationFeedClient(
            @Qualifier("inflationFeedRestClient") RestClient restClient, InflationFeedProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * Every published year for one country, oldest first.
     *
     * @param geo a Eurostat geo code, e.g. {@code HU}, {@code EA}, {@code US}
     * @throws InflationFeedException on any network failure, error status or unexpected answer
     */
    public SortedMap<Integer, BigDecimal> fetch(String geo) {
        EurostatDataset body;
        try {
            body = restClient.get().uri(uri -> buildUri(uri, geo)).retrieve().body(EurostatDataset.class);
        } catch (RestClientException ex) {
            throw new InflationFeedException(
                    "Could not read %s inflation from Eurostat: %s".formatted(geo, ex.getMessage()), ex);
        }
        if (body == null) {
            throw new InflationFeedException("Eurostat returned an empty response for " + geo);
        }
        SortedMap<Integer, BigDecimal> series = body.yearly();
        log.debug("Eurostat returned {} year(s) for {}", series.size(), geo);
        return series;
    }

    private URI buildUri(UriBuilder uri, String geo) {
        uri.path("/{dataset}").queryParam("format", "JSON").queryParam("lang", "EN");
        for (Map.Entry<String, String> filter : properties.getFilters().entrySet()) {
            uri.queryParam(filter.getKey(), filter.getValue());
        }
        uri.queryParam("geo", geo);
        return uri.build(properties.getDataset());
    }
}
