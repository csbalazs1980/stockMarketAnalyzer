package com.stockmarketanalyzer.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockmarketanalyzer.backend.inflation.feed.InflationFeedClient;
import com.stockmarketanalyzer.backend.inflation.feed.InflationFeedException;
import com.stockmarketanalyzer.backend.inflation.feed.InflationFeedProperties;
import com.stockmarketanalyzer.backend.inflation.web.dto.InflationFetchResponse;
import com.stockmarketanalyzer.backend.inflation.web.dto.InflationRateRequest;
import com.stockmarketanalyzer.backend.inflation.web.dto.InflationRateResponse;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * The inflation "Fetch" button with Eurostat stubbed out.
 *
 * <p>The rule under test: per currency, store only the years after the latest one held -- every
 * year when none is -- and never overwrite. The configured units here (XIA..XID) are added to the
 * ones in application.yaml (Spring merges maps); the stub answers the real geo codes with an empty
 * series, so the shared container's HUF/EUR/USD rows are never touched.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.inflation-feed.units.XIA=GA",
            "app.inflation-feed.units.XIB=GB",
            "app.inflation-feed.units.XIC=GC",
            "app.inflation-feed.units.XID=GD"
        })
@Import(InflationFeedApiIntegrationTest.StubFeedConfiguration.class)
class InflationFeedApiIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void resetStub() {
        StubInflationFeedClient.series.clear();
        StubInflationFeedClient.failOn = null;
    }

    @Test
    void aCurrencyWithNothingStoredGetsEveryPublishedYear() {
        StubInflationFeedClient.series.put("GA", series(2019, "1.5", 2020, "0.3", 2021, "2.6"));

        InflationFetchResponse response = fetch();

        InflationFetchResponse.UnitResult xia = unit(response, "XIA");
        assertThat(xia.geo()).isEqualTo("GA");
        assertThat(xia.previousLatestYear()).isNull();
        assertThat(xia.latestAvailableYear()).isEqualTo(2021);
        assertThat(xia.stored()).extracting(InflationRateResponse::year).containsExactly(2019, 2020, 2021);
        assertThat(get("XIA", 2020).rate()).isEqualByComparingTo("0.3");
    }

    @Test
    void onlyYearsAfterTheLatestStoredOneAreAddedAndNothingIsOverwritten() {
        // 2020 is the latest held, typed by hand with a figure that differs from the source;
        // 2018 is a gap before it.
        post(new InflationRateRequest("XIB", 2019, new BigDecimal("3.4")));
        post(new InflationRateRequest("XIB", 2020, new BigDecimal("9.9")));
        StubInflationFeedClient.series.put(
                "GB", series(2018, "2.9", 2019, "3.4", 2020, "3.3", 2021, "5.2", 2022, "15.3", 2023, "17.0"));

        InflationFetchResponse.UnitResult xib = unit(fetch(), "XIB");

        assertThat(xib.previousLatestYear()).isEqualTo(2020);
        assertThat(xib.latestAvailableYear()).isEqualTo(2023);
        assertThat(xib.stored()).extracting(InflationRateResponse::year).containsExactly(2021, 2022, 2023);
        assertThat(get("XIB", 2020).rate()).isEqualByComparingTo("9.9");
        assertThat(status("XIB", 2018)).isEqualTo(HttpStatus.NOT_FOUND);

        // A second press finds nothing newer and adds nothing.
        InflationFetchResponse.UnitResult again = unit(fetch(), "XIB");
        assertThat(again.previousLatestYear()).isEqualTo(2023);
        assertThat(again.stored()).isEmpty();
    }

    @Test
    void aCurrencyTheSourceHasNothingNewForReportsItsLatestYear() {
        post(new InflationRateRequest("XIC", 2024, new BigDecimal("2.0")));
        StubInflationFeedClient.series.put("GC", series(2023, "3.0", 2024, "2.0"));

        InflationFetchResponse response = fetch();

        InflationFetchResponse.UnitResult xic = unit(response, "XIC");
        assertThat(xic.previousLatestYear()).isEqualTo(2024);
        assertThat(xic.latestAvailableYear()).isEqualTo(2024);
        assertThat(xic.stored()).isEmpty();
        assertThat(response.source()).startsWith("Eurostat");
    }

    @Test
    void anUnreachableSeriesFailsThePressAndStoresNothing() {
        StubInflationFeedClient.series.put("GA", series(1990, "1.0"));
        StubInflationFeedClient.series.put("GD", series(2001, "4.0"));
        StubInflationFeedClient.failOn = "GC";

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/inflation-rates/fetch",
                HttpMethod.POST,
                new HttpEntity<>(TestSecurityConfiguration.adminHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(status("XID", 2001)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status("XIA", 1990)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anOrdinaryUserCannotFetch() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/inflation-rates/fetch",
                HttpMethod.POST,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private InflationFetchResponse fetch() {
        ResponseEntity<InflationFetchResponse> response = restTemplate.exchange(
                "/api/v1/inflation-rates/fetch",
                HttpMethod.POST,
                new HttpEntity<>(TestSecurityConfiguration.adminHeaders()),
                InflationFetchResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static InflationFetchResponse.UnitResult unit(InflationFetchResponse response, String unit) {
        return response.units().stream()
                .filter(result -> result.unit().equals(unit))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No result for " + unit));
    }

    private void post(InflationRateRequest request) {
        ResponseEntity<InflationRateResponse> response = restTemplate.exchange(
                "/api/v1/inflation-rates",
                HttpMethod.POST,
                new HttpEntity<>(request, TestSecurityConfiguration.adminHeaders()),
                InflationRateResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private InflationRateResponse get(String unit, int year) {
        ResponseEntity<InflationRateResponse> response = restTemplate.exchange(
                "/api/v1/inflation-rates/" + unit + "/" + year,
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                InflationRateResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private org.springframework.http.HttpStatusCode status(String unit, int year) {
        return restTemplate
                .exchange(
                        "/api/v1/inflation-rates/" + unit + "/" + year,
                        HttpMethod.GET,
                        new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                        String.class)
                .getStatusCode();
    }

    /** year, rate, year, rate, ... */
    private static SortedMap<Integer, BigDecimal> series(Object... yearsAndRates) {
        SortedMap<Integer, BigDecimal> series = new TreeMap<>();
        for (int i = 0; i < yearsAndRates.length; i += 2) {
            series.put((Integer) yearsAndRates[i], new BigDecimal((String) yearsAndRates[i + 1]));
        }
        return series;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubFeedConfiguration {

        @Bean
        @Primary
        InflationFeedClient stubInflationFeedClient(InflationFeedProperties properties) {
            return new StubInflationFeedClient(properties);
        }
    }

    static class StubInflationFeedClient extends InflationFeedClient {

        static final Map<String, SortedMap<Integer, BigDecimal>> series = new HashMap<>();
        static String failOn;

        StubInflationFeedClient(InflationFeedProperties properties) {
            super(RestClient.create(), properties);
        }

        @Override
        public SortedMap<Integer, BigDecimal> fetch(String geo) {
            if (geo.equals(failOn)) {
                throw new InflationFeedException("Could not read " + geo + " inflation from Eurostat: stubbed outage");
            }
            return series.getOrDefault(geo, new TreeMap<>());
        }
    }
}
