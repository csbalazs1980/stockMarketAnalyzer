package com.stockmarketanalyzer.backend.inflation.service;

import com.stockmarketanalyzer.backend.common.Units;
import com.stockmarketanalyzer.backend.inflation.feed.InflationFeedClient;
import com.stockmarketanalyzer.backend.inflation.feed.InflationFeedException;
import com.stockmarketanalyzer.backend.inflation.feed.InflationFeedProperties;
import com.stockmarketanalyzer.backend.inflation.web.dto.InflationFetchResponse;
import com.stockmarketanalyzer.backend.inflation.web.dto.InflationRateResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The fetch button: reads each configured currency's series from Eurostat and stores the years that
 * are newer than the latest one already held -- all of them when the currency has none. With 2020
 * as the latest stored year, 2021 onwards is added and 2020 and earlier are left exactly as they
 * are.
 *
 * <p><strong>Never overwrites.</strong> A year already held keeps its figure even when the source
 * has since revised it, and so does a gap before the latest year: the rule is "after the latest",
 * not "whatever is missing". Corrections stay in the administrator's hands (edit, or CSV import).
 *
 * <p><strong>Everything is fetched before anything is stored</strong>, and no transaction is held
 * across the network calls (the same trade as {@code RateFeedService}). One unreachable series
 * therefore fails the whole press with a 502 and stores nothing, rather than leaving a button that
 * half worked; each currency is then stored in its own short transaction.
 *
 * <p>No role check here: the endpoint is a POST under {@code /api/v1/inflation-rates}, which the
 * security configuration already restricts to ADMIN.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InflationFeedService {

    private final InflationFeedClient client;
    private final InflationFeedProperties properties;
    private final InflationRateService inflationRates;

    public InflationFetchResponse fetch() {
        if (!properties.isEnabled()) {
            throw new InflationFeedException("The inflation feed is disabled (app.inflation-feed.enabled is false).");
        }

        Map<String, String> geoByUnit = new LinkedHashMap<>();
        properties.getUnits().forEach((unit, geo) -> geoByUnit.put(Units.normalise(unit), geo.trim()));

        Map<String, SortedMap<Integer, BigDecimal>> seriesByUnit = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : geoByUnit.entrySet()) {
            seriesByUnit.put(entry.getKey(), client.fetch(entry.getValue()));
        }

        List<InflationFetchResponse.UnitResult> results = new ArrayList<>();
        int total = 0;
        for (Map.Entry<String, SortedMap<Integer, BigDecimal>> entry : seriesByUnit.entrySet()) {
            SortedMap<Integer, BigDecimal> series = entry.getValue();
            InflationRateService.NewYears added = inflationRates.storeNewYears(entry.getKey(), series);
            total += added.stored().size();
            results.add(new InflationFetchResponse.UnitResult(
                    entry.getKey(),
                    geoByUnit.get(entry.getKey()),
                    added.previousLatestYear(),
                    series.isEmpty() ? null : series.lastKey(),
                    added.stored().stream().map(InflationRateResponse::from).toList()));
        }

        log.info("Inflation fetch from {}: {} row(s) stored for {}", properties.sourceLabel(), total, geoByUnit.keySet());
        return new InflationFetchResponse(properties.sourceLabel(), total, results);
    }
}
