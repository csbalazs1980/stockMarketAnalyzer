package com.stockmarketanalyzer.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The admin surface fails open in the most expensive way if its rule is wrong: {@code GET}s on
 * {@code /api/v1/**} are readable by any valid token, so without an explicit rule every signed-in
 * USER could read the whole realm's user directory. That is asserted here rather than assumed.
 *
 * <p>The ADMIN case deliberately asserts a 502. No Keycloak is running, so the call cannot
 * succeed -- but *which* failure it is matters: 502 means the authorization rule let the
 * administrator through and the identity provider was the thing that could not be reached, which
 * is exactly the boundary these tests exist to pin down.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserAdminSecurityIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void rejectsAnonymousAccessToTheUserDirectory() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/admin/users", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void hidesTheUserDirectoryFromOrdinaryUsers() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"status\":403");
    }

    @Test
    void refusesUserCreationFromOrdinaryUsers() {
        Map<String, Object> body = Map.of(
                "username", "someone", "password", "correct-horse", "roles", List.of("USER"));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users",
                HttpMethod.POST,
                new HttpEntity<>(body, TestSecurityConfiguration.userHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void letsAdminsThroughAndReportsAnUnreachableKeycloakAsBadGateway() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.adminHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).contains("User administration unavailable");
    }
}
