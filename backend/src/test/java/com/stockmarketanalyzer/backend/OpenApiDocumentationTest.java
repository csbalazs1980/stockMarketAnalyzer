package com.stockmarketanalyzer.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Guards the published contract: springdoc silently serves an empty document when scanning
 * misfires, so assert the real endpoints are there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocumentationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void publishesTheV1Contract() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs/v1", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"openapi\"")
                .contains("Stock Market Analyzer API")
                .contains("/api/v1/instruments")
                .contains("/api/v1/instruments/{symbol}/prices/summary")
                .contains("InstrumentRequest")
                .contains("PriceBarRequest");
    }

    @Test
    void publishesTheKeycloakSecurityScheme() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs/v1", String.class);

        assertThat(response.getBody())
                .contains("\"keycloak\"")
                .contains("authorizationCode")
                .contains("/protocol/openid-connect/auth")
                .contains("/protocol/openid-connect/token");
    }

    @Test
    void excludesActuatorFromTheContract() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs/v1", String.class);

        assertThat(response.getBody()).doesNotContain("/actuator");
    }

    @Test
    void servesSwaggerUi() {
        ResponseEntity<String> response = restTemplate.getForEntity("/swagger-ui/index.html", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("swagger-ui");
    }
}
