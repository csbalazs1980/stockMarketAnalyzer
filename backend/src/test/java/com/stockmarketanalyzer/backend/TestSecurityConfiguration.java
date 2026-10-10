package com.stockmarketanalyzer.backend;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Replaces the real {@link JwtDecoder} with a stub so the suite never needs a running
 * Keycloak. Supplying this bean also stops Spring Boot's resource-server auto-configuration
 * from resolving the issuer, which would otherwise be a network call during every test run.
 *
 * <p>The stub still produces genuine {@link Jwt} objects with a {@code realm_access.roles}
 * claim, so {@code KeycloakRealmRoleConverter} and every {@code hasRole} rule are exercised
 * exactly as they are in production — only the signature check is skipped.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfiguration {

    public static final String ADMIN_TOKEN = "test-token-admin";
    public static final String USER_TOKEN = "test-token-user";

    /** Authorization header carrying a token with the ADMIN and USER realm roles. */
    public static HttpHeaders adminHeaders() {
        return bearer(ADMIN_TOKEN);
    }

    /** Authorization header carrying a token with only the USER realm role. */
    public static HttpHeaders userHeaders() {
        return bearer(USER_TOKEN);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> switch (token) {
            case ADMIN_TOKEN -> stubJwt("sma-admin", List.of("ADMIN", "USER"));
            case USER_TOKEN -> stubJwt("sma-user", List.of("USER"));
            default -> throw new BadJwtException("Unrecognised test token: " + token);
        };
    }

    private static Jwt stubJwt(String username, List<String> realmRoles) {
        Instant issuedAt = Instant.now();
        return Jwt.withTokenValue("stub-" + username)
                .header("alg", "none")
                .subject(UUID.nameUUIDFromBytes(username.getBytes(StandardCharsets.UTF_8))
                        .toString())
                .issuer("https://keycloak.test/realms/stockmarket")
                .audience(List.of("stockmarket-api"))
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", realmRoles))
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(1, ChronoUnit.HOURS))
                .build();
    }
}
