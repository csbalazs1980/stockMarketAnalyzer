package com.stockmarketanalyzer.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockmarketanalyzer.backend.inflation.web.dto.InflationRateRequest;
import com.stockmarketanalyzer.backend.inflation.web.dto.InflationRateResponse;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Inflation is shared reference data, so it lives outside {@code /api/v1/wealth/**} and inherits
 * the standard rule: any token reads, only ADMIN writes. The 403 case is the one that would
 * silently regress if the endpoint were ever moved under the wealth path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InflationRateApiIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void storesAYearAndReplacesItOnRepost() {
        ResponseEntity<InflationRateResponse> created = post(
                new InflationRateRequest("huf", 2025, new BigDecimal("4.6")), TestSecurityConfiguration.adminHeaders());

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().unit()).isEqualTo("HUF");
        assertThat(created.getBody().rate()).isEqualByComparingTo("4.6");

        // A provisional figure superseded by the final print: same row, new value.
        ResponseEntity<InflationRateResponse> revised = post(
                new InflationRateRequest("HUF", 2025, new BigDecimal("3.9")), TestSecurityConfiguration.adminHeaders());

        assertThat(revised.getBody()).isNotNull();
        assertThat(revised.getBody().id()).isEqualTo(created.getBody().id());
        assertThat(revised.getBody().rate()).isEqualByComparingTo("3.9");

        // Lookup is by the natural key and case-insensitive on the currency.
        ResponseEntity<InflationRateResponse> fetched = restTemplate.exchange(
                "/api/v1/inflation-rates/huf/2025",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                InflationRateResponse.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().rate()).isEqualByComparingTo("3.9");
    }

    @Test
    void acceptsDeflationButNotAFallOfMoreThanEverything() {
        ResponseEntity<InflationRateResponse> deflation = post(
                new InflationRateRequest("EUR", 2015, new BigDecimal("-0.6")),
                TestSecurityConfiguration.adminHeaders());
        assertThat(deflation.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> impossible = restTemplate.exchange(
                "/api/v1/inflation-rates",
                HttpMethod.POST,
                new HttpEntity<>(
                        new InflationRateRequest("EUR", 2016, new BigDecimal("-150")),
                        TestSecurityConfiguration.adminHeaders()),
                String.class);
        assertThat(impossible.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refusesAWriteFromANonAdmin() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/inflation-rates",
                HttpMethod.POST,
                new HttpEntity<>(
                        new InflationRateRequest("USD", 2025, new BigDecimal("2.9")),
                        TestSecurityConfiguration.userHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private ResponseEntity<InflationRateResponse> post(
            InflationRateRequest request, org.springframework.http.HttpHeaders headers) {
        return restTemplate.exchange(
                "/api/v1/inflation-rates",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                InflationRateResponse.class);
    }
}
