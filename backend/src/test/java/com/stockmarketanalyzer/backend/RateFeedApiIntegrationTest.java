package com.stockmarketanalyzer.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.stockmarketanalyzer.backend.common.PageResponse;
import com.stockmarketanalyzer.backend.wealth.ratefeed.CurrencyPair;
import com.stockmarketanalyzer.backend.wealth.ratefeed.RateFeedClient;
import com.stockmarketanalyzer.backend.wealth.ratefeed.RateFeedException;
import com.stockmarketanalyzer.backend.wealth.ratefeed.RateFeedProperties;
import com.stockmarketanalyzer.backend.wealth.ratefeed.RateQuote;
import com.stockmarketanalyzer.backend.wealth.web.dto.ExchangeRateResponse;
import com.stockmarketanalyzer.backend.wealth.web.dto.RateRefreshResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * The "fetch latest" button, with the feed stubbed out.
 *
 * <p>The point of the stub is not speed, it is determinism: a test that called the real service
 * would fail on a public holiday, on a network hiccup, and every time somebody else's server
 * changed a number. What actually needs proving is what this application does with a quote once
 * it has one -- above all that it stores the feed's <em>publication</em> date rather than today's,
 * which is precisely the part a live test could not assert without reimplementing the rule.
 *
 * <p>The Postgres container is static and shared with the other integration tests, so this class
 * uses units nothing else touches.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.rate-feed.pairs=XRA:XRB,XRC:XRB", "app.rate-feed.provider=STUB"})
@Import(RateFeedApiIntegrationTest.StubRateFeedConfiguration.class)
class RateFeedApiIntegrationTest extends AbstractPostgresIntegrationTest {

    /** Deliberately not today: the whole rule is that the feed's date wins. */
    private static final LocalDate PUBLISHED = LocalDate.of(2026, 2, 13);

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void resetStub() {
        StubRateFeedClient.failWith = null;
        StubRateFeedClient.quotes = List.of(
                new RateQuote(PUBLISHED, "XRA", "XRB", new BigDecimal("314.55")),
                new RateQuote(PUBLISHED, "XRC", "XRB", new BigDecimal("365.22")));
    }

    @Test
    void storesEachQuoteUnderTheDateTheFeedPublishedIt() {
        RateRefreshResponse response = refresh();

        assertThat(response.provider()).isEqualTo("STUB");
        assertThat(response.requested()).isEqualTo(2);
        assertThat(response.stored()).isEqualTo(2);
        assertThat(response.rates())
                .extracting(ExchangeRateResponse::sourceUnit, ExchangeRateResponse::destinationUnit)
                .containsExactlyInAnyOrder(tuple("XRA", "XRB"), tuple("XRC", "XRB"));
        // Not LocalDate.now(): asked on a Sunday the feed answers with Friday's rate, and storing
        // that under Sunday would record a quote that was never published.
        assertThat(response.rates()).allMatch(rate -> PUBLISHED.equals(rate.rateDate()));

        // And the stored rate is immediately usable by valuation.
        ResponseEntity<ExchangeRateResponse> inForce = restTemplate.exchange(
                "/api/v1/wealth/exchange-rates/in-force?sourceUnit=XRA&destinationUnit=XRB&on=" + PUBLISHED,
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                ExchangeRateResponse.class);
        assertThat(inForce.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(inForce.getBody()).isNotNull();
        assertThat(inForce.getBody().rate()).isEqualByComparingTo("314.55");
    }

    @Test
    void pressingTheButtonAgainRewritesTheSameRowsRatherThanAddingMore() {
        StubRateFeedClient.quotes = List.of(new RateQuote(PUBLISHED, "XSA", "XSB", new BigDecimal("314.55")));
        refresh();
        assertThat(storedRates("XSB")).hasSize(1);

        // A corrected number for the same pair and day, which is what a second press during the
        // day amounts to. The store is an upsert on (pair, date), so it replaces rather than adds.
        StubRateFeedClient.quotes = List.of(new RateQuote(PUBLISHED, "XSA", "XSB", new BigDecimal("316.10")));
        assertThat(refresh().stored()).isEqualTo(1);

        List<ExchangeRateResponse> stored = storedRates("XSB");
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).rate()).isEqualByComparingTo("316.10");
    }

    @Test
    void aFeedFailureIsABadGatewayAndStoresNothing() {
        StubRateFeedClient.failWith = "connection reset";

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/wealth/exchange-rates/refresh",
                HttpMethod.POST,
                new HttpEntity<>(TestSecurityConfiguration.adminHeaders()),
                String.class);

        // Nothing here is the caller's fault and nothing is wrong with this application.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).contains("connection reset");
        assertThat(storedRates("XTB")).isEmpty();
    }

    @Test
    void fetchedRatesAreGlobalSoEveryUserSeesThem() {
        StubRateFeedClient.quotes = List.of(new RateQuote(PUBLISHED, "XUA", "XUB", new BigDecimal("1.16")));
        refresh();

        // Pressed by the administrator, read by an ordinary user -- a different Keycloak subject.
        List<ExchangeRateResponse> seenByUser = storedRates("XUB");
        assertThat(seenByUser).hasSize(1);
        assertThat(seenByUser.get(0).global()).isTrue();

        // And an ordinary user's own list (global=false) does not contain it.
        ResponseEntity<PageResponse<ExchangeRateResponse>> ownOnly = restTemplate.exchange(
                "/api/v1/wealth/exchange-rates?global=false&destinationUnit=XUB",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                new ParameterizedTypeReference<PageResponse<ExchangeRateResponse>>() {});
        assertThat(ownOnly.getBody()).isNotNull();
        assertThat(ownOnly.getBody().content()).isEmpty();
    }

    @Test
    void anOrdinaryUserCannotRefreshTheSharedRates() {
        StubRateFeedClient.quotes = List.of(new RateQuote(PUBLISHED, "XVA", "XVB", new BigDecimal("2")));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/wealth/exchange-rates/refresh",
                HttpMethod.POST,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(storedRates("XVB")).isEmpty();
    }

    @Test
    void rejectsAnUnauthenticatedRefresh() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/wealth/exchange-rates/refresh", HttpMethod.POST, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** As an administrator: the feed now writes global rates, which is an ADMIN action. */
    private RateRefreshResponse refresh() {
        ResponseEntity<RateRefreshResponse> response = restTemplate.exchange(
                "/api/v1/wealth/exchange-rates/refresh",
                HttpMethod.POST,
                new HttpEntity<>(TestSecurityConfiguration.adminHeaders()),
                RateRefreshResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private List<ExchangeRateResponse> storedRates(String destinationUnit) {
        ResponseEntity<PageResponse<ExchangeRateResponse>> response = restTemplate.exchange(
                "/api/v1/wealth/exchange-rates?destinationUnit=" + destinationUnit,
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                new ParameterizedTypeReference<PageResponse<ExchangeRateResponse>>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().content();
    }

    /**
     * Replaces the real client with one that answers from a field. Marked {@code @Primary} rather
     * than excluding the real bean, so the real one is still constructed — which keeps this test
     * honest about {@code RateFeedConfiguration} being wirable.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubRateFeedConfiguration {

        @Bean
        @Primary
        RateFeedClient stubRateFeedClient(RateFeedProperties properties) {
            return new StubRateFeedClient(properties);
        }
    }

    static class StubRateFeedClient extends RateFeedClient {

        static List<RateQuote> quotes = List.of();
        static String failWith;

        StubRateFeedClient(RateFeedProperties properties) {
            super(RestClient.create(), properties);
        }

        @Override
        public List<RateQuote> fetch(Collection<CurrencyPair> pairs) {
            if (failWith != null) {
                throw new RateFeedException("Could not read rates from the rate feed: " + failWith);
            }
            return quotes;
        }
    }
}
