package com.stockmarketanalyzer.backend.auth;

import java.time.Duration;

/**
 * What Keycloak hands back from a token request, reduced to the four fields this application
 * uses. Deliberately not a Jackson-annotated mirror of the OIDC response: the raw payload is
 * read as a map in {@link KeycloakTokenClient}, which keeps the class free of the Jackson 2
 * vs 3 question that Spring Boot 4 leaves open.
 */
public record TokenSet(String accessToken, String refreshToken, Duration accessExpiry, Duration refreshExpiry) {}
