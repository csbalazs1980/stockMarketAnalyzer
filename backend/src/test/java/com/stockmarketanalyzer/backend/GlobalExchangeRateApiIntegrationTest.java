package com.stockmarketanalyzer.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockmarketanalyzer.backend.common.PageResponse;
import com.stockmarketanalyzer.backend.wealth.web.dto.ExchangeRateRequest;
import com.stockmarketanalyzer.backend.wealth.web.dto.ExchangeRateResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Global (owner-less) exchange rates next to personal ones.
 *
 * <p>Each test uses its own G-prefixed units: the Postgres container is shared, and a global rate
 * is by definition visible to every other test's users.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GlobalExchangeRateApiIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String RATES = "/api/v1/wealth/exchange-rates";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void aGlobalRateIsSeenAndUsedByEveryUser() {
        ExchangeRateResponse stored = post(
                TestSecurityConfiguration.adminHeaders(),
                new ExchangeRateRequest("GAA", "GAB", new BigDecimal("10"), LocalDate.of(2026, 3, 1), true));
        assertThat(stored.global()).isTrue();

        List<ExchangeRateResponse> seen = list("?sourceUnit=GAA");
        assertThat(seen).singleElement().satisfies(rate -> assertThat(rate.global()).isTrue());

        ExchangeRateResponse inForce = inForce("GAA", "GAB", "2026-03-15");
        assertThat(inForce.rate()).isEqualByComparingTo("10");
        assertThat(inForce.global()).isTrue();
    }

    @Test
    void aPersonalRateAlwaysWinsEvenWhenOlder() {
        post(TestSecurityConfiguration.adminHeaders(),
                new ExchangeRateRequest("GBA", "GBB", new BigDecimal("20"), LocalDate.of(2026, 5, 1), true));
        post(TestSecurityConfiguration.userHeaders(),
                new ExchangeRateRequest("GBA", "GBB", new BigDecimal("7"), LocalDate.of(2026, 1, 1)));

        // The global quote is four months newer; the user's own still applies.
        ExchangeRateResponse inForce = inForce("GBA", "GBB", "2026-06-01");
        assertThat(inForce.rate()).isEqualByComparingTo("7");
        assertThat(inForce.global()).isFalse();

        // Before either quote existed there is nothing at all.
        ResponseEntity<String> tooEarly = restTemplate.exchange(
                RATES + "/in-force?sourceUnit=GBA&destinationUnit=GBB&on=2025-12-01",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                String.class);
        assertThat(tooEarly.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Both rows are listed, one of each kind; global=false narrows to the user's own.
        assertThat(list("?sourceUnit=GBA"))
                .extracting(ExchangeRateResponse::global)
                .containsExactlyInAnyOrder(true, false);
        assertThat(list("?sourceUnit=GBA&global=false"))
                .extracting(ExchangeRateResponse::global)
                .containsExactly(false);
    }

    @Test
    void onlyAnAdministratorWritesGlobalRates() {
        ResponseEntity<String> create = restTemplate.exchange(
                RATES,
                HttpMethod.POST,
                new HttpEntity<>(
                        new ExchangeRateRequest("GCA", "GCB", new BigDecimal("3"), LocalDate.of(2026, 2, 1), true),
                        TestSecurityConfiguration.userHeaders()),
                String.class);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(list("?sourceUnit=GCA")).isEmpty();

        ExchangeRateResponse global = post(
                TestSecurityConfiguration.adminHeaders(),
                new ExchangeRateRequest("GCA", "GCB", new BigDecimal("3"), LocalDate.of(2026, 2, 1), true));
        assertThat(delete(global.id(), TestSecurityConfiguration.userHeaders())).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(delete(global.id(), TestSecurityConfiguration.adminHeaders())).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void importingAGlobalFileIsAnAdministratorAction() {
        String csv = "sourceUnit,destinationUnit,rate,rateDate\r\nGDA,GDB,4,2026-04-01\r\n";

        assertThat(importCsv(csv, TestSecurityConfiguration.userHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(importCsv(csv, TestSecurityConfiguration.adminHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(list("?sourceUnit=GDA")).singleElement().satisfies(rate -> assertThat(rate.global()).isTrue());

        // The user's export is their own data: the global row is not in it.
        ResponseEntity<String> export = restTemplate.exchange(
                RATES + "/export",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                String.class);
        assertThat(export.getBody()).doesNotContain("GDA");
    }

    private ExchangeRateResponse post(HttpHeaders headers, ExchangeRateRequest request) {
        ResponseEntity<ExchangeRateResponse> response = restTemplate.exchange(
                RATES, HttpMethod.POST, new HttpEntity<>(request, headers), ExchangeRateResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private List<ExchangeRateResponse> list(String query) {
        ResponseEntity<PageResponse<ExchangeRateResponse>> response = restTemplate.exchange(
                RATES + query,
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                new ParameterizedTypeReference<PageResponse<ExchangeRateResponse>>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().content();
    }

    private ExchangeRateResponse inForce(String source, String destination, String on) {
        ResponseEntity<ExchangeRateResponse> response = restTemplate.exchange(
                RATES + "/in-force?sourceUnit=%s&destinationUnit=%s&on=%s".formatted(source, destination, on),
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                ExchangeRateResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private HttpStatus delete(Long id, HttpHeaders headers) {
        return HttpStatus.valueOf(restTemplate
                .exchange(RATES + "/" + id, HttpMethod.DELETE, new HttpEntity<>(headers), String.class)
                .getStatusCode()
                .value());
    }

    private ResponseEntity<String> importCsv(String content, HttpHeaders headers) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "rates.csv";
            }
        });
        body.add("global", "true");
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange(RATES + "/import", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
