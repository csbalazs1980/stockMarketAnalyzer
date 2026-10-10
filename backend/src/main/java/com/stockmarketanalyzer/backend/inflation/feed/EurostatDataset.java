package com.stockmarketanalyzer.backend.inflation.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The part of Eurostat's JSON-stat 2.0 answer this application reads.
 *
 * <p>JSON-stat stores a cube as one flat {@code value} object keyed by position: the position of a
 * cell is its index in each dimension, combined row-major using {@code size}. The request filters
 * every dimension but {@code time} down to one category, so in practice the position <em>is</em> the
 * time index -- but that is checked, not assumed, because a filter that silently stopped matching
 * (a renamed dimension, say) would otherwise mix two series into one.
 *
 * <p>Plain records with {@code @JsonIgnoreProperties} rather than a {@code JsonNode}: the
 * annotation package is the same in Jackson 2 and 3, a tree type is not, and Boot 4 makes which one
 * the converter uses a classpath question.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EurostatDataset(
        List<String> id, List<Integer> size, Map<String, Dimension> dimension, Map<String, BigDecimal> value) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dimension(Category category) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Category(Map<String, Integer> index, Map<String, String> label) {}

    private static final String TIME = "time";

    /**
     * The series as year -> rate, oldest first. Years Eurostat lists but has no value for (a lagging
     * country's current year) are left out rather than reported as zero.
     *
     * @throws InflationFeedException when the answer is not a single annual series
     */
    public SortedMap<Integer, BigDecimal> yearly() {
        if (id == null || size == null || dimension == null || id.size() != size.size()) {
            throw new InflationFeedException("Eurostat answered without the expected id/size/dimension structure");
        }
        int timeAxis = id.indexOf(TIME);
        if (timeAxis < 0) {
            throw new InflationFeedException("Eurostat answered without a time dimension");
        }
        for (int axis = 0; axis < size.size(); axis++) {
            if (axis != timeAxis && size.get(axis) != 1) {
                throw new InflationFeedException(
                        "Eurostat answered with %d categories in '%s' where one was asked for; check app.inflation-feed.filters"
                                .formatted(size.get(axis), id.get(axis)));
            }
        }
        Dimension time = dimension.get(TIME);
        if (time == null || time.category() == null || time.category().index() == null) {
            throw new InflationFeedException("Eurostat answered without a time index");
        }

        SortedMap<Integer, BigDecimal> series = new TreeMap<>();
        if (value == null) {
            return series;
        }
        for (Map.Entry<String, Integer> period : time.category().index().entrySet()) {
            BigDecimal rate = value.get(String.valueOf(period.getValue()));
            if (rate == null) {
                continue;
            }
            int year;
            try {
                year = Integer.parseInt(period.getKey().trim());
            } catch (NumberFormatException ex) {
                throw new InflationFeedException(
                        "Eurostat answered with a non-annual period '%s'".formatted(period.getKey()), ex);
            }
            series.put(year, rate);
        }
        return series;
    }
}
