package com.stockmarketanalyzer.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockmarketanalyzer.backend.common.PageResponse;
import com.stockmarketanalyzer.backend.dividend.web.dto.DividendRequest;
import com.stockmarketanalyzer.backend.dividend.web.dto.DividendResponse;
import com.stockmarketanalyzer.backend.dividend.web.dto.DividendSummaryResponse;
import com.stockmarketanalyzer.backend.instrument.domain.AssetType;
import com.stockmarketanalyzer.backend.instrument.web.dto.InstrumentRequest;
import com.stockmarketanalyzer.backend.instrument.web.dto.InstrumentResponse;
import com.stockmarketanalyzer.backend.wealth.web.dto.ExchangeRateRequest;
import com.stockmarketanalyzer.backend.wealth.web.dto.ExchangeRateResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Exercises the dividend slice end to end. Instruments are shared reference data, so an ADMIN
 * creates them; the dividends themselves are written by an ordinary USER, valued from that
 * user's own exchange rates, and invisible to anybody else.
 *
 * <p>The Postgres container is static and shared across the methods of this class, so each test
 * works on its own instrument and its own dates rather than on an unfiltered view.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DividendApiIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final LocalDate RATE_DATE = LocalDate.of(2026, 8, 5);

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void recordsDividendsAndSumsWhatTheFilterMatches() {
        InstrumentResponse apple = createInstrument("AAPL", "Apple Inc.", "USD");
        storeRate(new ExchangeRateRequest("USD", "HUF", new BigDecimal("314"), RATE_DATE));

        DividendResponse usd = createDividend(
                new DividendRequest(apple.id(), new BigDecimal("42.75"), "usd", LocalDate.of(2026, 8, 15)));
        assertThat(usd.instrumentSymbol()).isEqualTo("AAPL");
        // The unit is normalised on the way in, like every other unit in this application.
        assertThat(usd.unit()).isEqualTo("USD");
        assertThat(usd.valueUnit()).isEqualTo("HUF");
        // Valued at the newest rate quoted on or before the payment date.
        assertThat(usd.currentValue()).isEqualByComparingTo("13423.5");

        // Paid in a currency the owner has never quoted: absent, not zero.
        DividendResponse eur = createDividend(
                new DividendRequest(apple.id(), new BigDecimal("10"), "EUR", LocalDate.of(2026, 8, 20)));
        assertThat(eur.currentValue()).isNull();

        List<DividendResponse> august = page("/api/v1/dividends?from=2026-08-01&to=2026-08-31");
        assertThat(august).hasSize(2);
        // Newest payment first.
        assertThat(august.get(0).paymentDate()).isEqualTo(LocalDate.of(2026, 8, 20));

        // Filtering by currency narrows it to the convertible one.
        assertThat(page("/api/v1/dividends?from=2026-08-01&to=2026-08-31&unit=usd"))
                .hasSize(1);

        DividendSummaryResponse summary = summary("/api/v1/dividends/summary?from=2026-08-01&to=2026-08-31");
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.total()).isEqualByComparingTo("13423.5");
        // One matching row had no rate, so the total is a lower bound rather than the answer.
        assertThat(summary.totalPartial()).isTrue();
        assertThat(summary.valueUnit()).isEqualTo("HUF");

        // Asking in USD leaves the dollar dividend alone and still cannot price the euro one.
        DividendSummaryResponse inUsd =
                summary("/api/v1/dividends/summary?from=2026-08-01&to=2026-08-31&valueIn=USD");
        assertThat(inUsd.total()).isEqualByComparingTo("42.75");
        assertThat(inUsd.totalPartial()).isTrue();

        // A different Keycloak subject shares the database but sees none of these rows.
        ResponseEntity<PageResponse<DividendResponse>> otherUser = restTemplate.exchange(
                "/api/v1/dividends",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.adminHeaders()),
                new ParameterizedTypeReference<PageResponse<DividendResponse>>() {});
        assertThat(otherUser.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otherUser.getBody()).isNotNull();
        assertThat(otherUser.getBody().content()).isEmpty();

        ResponseEntity<String> foreignRow = restTemplate.exchange(
                "/api/v1/dividends/" + usd.id(),
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.adminHeaders()),
                String.class);
        assertThat(foreignRow.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void refusesToDeleteAnInstrumentSomebodyHasBeenPaidBy() {
        InstrumentResponse microsoft = createInstrument("MSFT", "Microsoft Corporation", "USD");
        createDividend(new DividendRequest(microsoft.id(), new BigDecimal("5"), "USD", LocalDate.of(2026, 5, 10)));

        // A price bar would cascade away with the instrument; a dividend is typed-in history,
        // so the foreign key is RESTRICT and the delete is refused instead.
        ResponseEntity<String> refused = restTemplate.exchange(
                "/api/v1/instruments/MSFT",
                HttpMethod.DELETE,
                new HttpEntity<>(TestSecurityConfiguration.adminHeaders()),
                String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Still there, and still ours.
        assertThat(page("/api/v1/dividends?instrumentId=" + microsoft.id())).hasSize(1);
    }

    @Test
    void rejectsADividendForAnUnknownInstrument() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/dividends",
                HttpMethod.POST,
                new HttpEntity<>(
                        new DividendRequest(987_654L, new BigDecimal("1"), "USD", RATE_DATE),
                        TestSecurityConfiguration.userHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("No instrument found");
    }

    @Test
    void rejectsAnUnauthenticatedWrite() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/dividends",
                HttpMethod.POST,
                new HttpEntity<>(new DividendRequest(1L, new BigDecimal("1"), "USD", RATE_DATE)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private InstrumentResponse createInstrument(String symbol, String name, String currency) {
        ResponseEntity<InstrumentResponse> response = restTemplate.exchange(
                "/api/v1/instruments",
                HttpMethod.POST,
                new HttpEntity<>(
                        new InstrumentRequest(symbol, name, "NASDAQ", currency, AssetType.STOCK, null, true),
                        TestSecurityConfiguration.adminHeaders()),
                InstrumentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private DividendResponse createDividend(DividendRequest request) {
        ResponseEntity<DividendResponse> response = restTemplate.exchange(
                "/api/v1/dividends",
                HttpMethod.POST,
                new HttpEntity<>(request, TestSecurityConfiguration.userHeaders()),
                DividendResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private void storeRate(ExchangeRateRequest request) {
        ResponseEntity<ExchangeRateResponse> response = restTemplate.exchange(
                "/api/v1/wealth/exchange-rates",
                HttpMethod.POST,
                new HttpEntity<>(request, TestSecurityConfiguration.userHeaders()),
                ExchangeRateResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private List<DividendResponse> page(String path) {
        ResponseEntity<PageResponse<DividendResponse>> response = restTemplate.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(headers()),
                new ParameterizedTypeReference<PageResponse<DividendResponse>>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().content();
    }

    private DividendSummaryResponse summary(String path) {
        ResponseEntity<DividendSummaryResponse> response = restTemplate.exchange(
                path, HttpMethod.GET, new HttpEntity<>(headers()), DividendSummaryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static HttpHeaders headers() {
        return TestSecurityConfiguration.userHeaders();
    }
}
