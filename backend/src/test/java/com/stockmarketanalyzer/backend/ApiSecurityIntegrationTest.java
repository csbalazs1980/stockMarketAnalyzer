package com.stockmarketanalyzer.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockmarketanalyzer.backend.instrument.domain.AssetType;
import com.stockmarketanalyzer.backend.instrument.web.dto.InstrumentRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The authorization rules are easy to get subtly wrong — a stray permitAll, a role name that
 * silently never matches — and a wrong rule fails open, so it is asserted rather than assumed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSecurityIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final InstrumentRequest SAMPLE =
            new InstrumentRequest("nvda", "NVIDIA Corporation", "NASDAQ", "usd", AssetType.STOCK, "Technology", true);

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void rejectsAnonymousReadWithProblemDetail() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/instruments", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(response.getBody()).contains("\"status\":401").contains("Unauthorized");
    }

    @Test
    void rejectsGarbageToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("not-a-real-token");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/instruments", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void allowsAuthenticatedRead() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/instruments",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void refusesWritesFromNonAdmin() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/instruments",
                HttpMethod.POST,
                new HttpEntity<>(SAMPLE, TestSecurityConfiguration.userHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"status\":403").contains("Forbidden");
    }

    @Test
    void leavesHealthAndContractPublic() {
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/v3/api-docs/v1", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
