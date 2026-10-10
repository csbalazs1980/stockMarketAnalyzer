package com.stockmarketanalyzer.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The admin URI is derived rather than configured, so that the Keycloak whose tokens are accepted
 * and the Keycloak whose users are administered are the same server by construction. The
 * derivation is one string operation, and it is the kind that is wrong in production and nowhere
 * else, so it is pinned here.
 */
class KeycloakAdminClientTest {

    @Test
    void insertsAdminIntoTheIssuerUri() {
        assertThat(KeycloakAdminClient.resolveRealmUri(null, "http://localhost:8180/realms/stockmarket"))
                .isEqualTo("http://localhost:8180/admin/realms/stockmarket");
    }

    @Test
    void toleratesATrailingSlash() {
        assertThat(KeycloakAdminClient.resolveRealmUri(null, "https://id.example.com/realms/sma/"))
                .isEqualTo("https://id.example.com/admin/realms/sma");
    }

    /** A reverse proxy may serve Keycloak under a path of its own; only the last /realms/ counts. */
    @Test
    void usesTheLastRealmsSegment() {
        assertThat(KeycloakAdminClient.resolveRealmUri(null, "https://example.com/realms/proxy/realms/sma"))
                .isEqualTo("https://example.com/realms/proxy/admin/realms/sma");
    }

    @Test
    void prefersAnExplicitOverride() {
        assertThat(KeycloakAdminClient.resolveRealmUri(
                        "https://admin.example.com/admin/realms/sma/", "http://localhost:8180/realms/stockmarket"))
                .isEqualTo("https://admin.example.com/admin/realms/sma");
    }

    @Test
    void refusesAnIssuerItCannotUnderstand() {
        assertThatThrownBy(() -> KeycloakAdminClient.resolveRealmUri("", "https://id.example.com/oauth2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.admin.realm-uri");
    }
}
