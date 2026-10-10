package com.stockmarketanalyzer.backend.inflation.feed;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from the {@code app.inflation-feed.*} keys in application.yaml.
 *
 * <p>The configuration in application.yaml reads Eurostat's annual HICP dataset, which needs no API key and covers all three
 * currencies with one method: {@code HU} for HUF, {@code EA} (the euro area in its changing
 * composition -- each year counts the countries that used the euro that year) for EUR, and
 * {@code US} for USD. The US series is Eurostat's HICP-comparable figure, not the headline CPI-U,
 * and lags: as of 2026-10-10 it ended at 2024 while HU and EA had 2025.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.inflation-feed")
public class InflationFeedProperties {

    /** Turn the fetch endpoint off entirely (it then answers 502 with an explanation). */
    private boolean enabled = true;

    /** Eurostat's dissemination API root, without a trailing slash. */
    private String baseUrl = "https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data";

    /**
     * The dataset code. {@code prc_hicp_ainr} replaced the discontinued {@code prc_hicp_aind} in
     * 2026 (ECOICOP ver. 2); the old code still answered on 2026-10-10 but no longer updates.
     */
    private String dataset = "prc_hicp_ainr";

    /**
     * Fixed query filters selecting one series per country: the annual average rate of change of
     * the all-items index. The classification dimension is {@code coicop18} in the new dataset
     * ({@code coicop} with value {@code CP00} in the old one), which is why this is a map rather
     * than two named fields.
     *
     * <p>Empty here, set in application.yaml: Spring <em>merges</em> a bound map into a non-empty
     * default, so a default here could never be removed by configuration.
     */
    private Map<String, String> filters = new LinkedHashMap<>();

    /**
     * Which Eurostat {@code geo} code each of the application's currencies is read from. A currency
     * left out here is simply not fetched; one added here is fetched on the next press. Empty
     * here and set in application.yaml, for the same merging reason as {@link #filters}.
     */
    private Map<String, String> units = new LinkedHashMap<>();

    private Duration connectTimeout = Duration.ofSeconds(5);

    private Duration readTimeout = Duration.ofSeconds(20);

    /** Human-readable name of the source, shown in the response and the toast. */
    public String sourceLabel() {
        return "Eurostat " + dataset;
    }
}
