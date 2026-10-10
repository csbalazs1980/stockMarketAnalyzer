package com.stockmarketanalyzer.backend.admin;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from the {@code app.admin.*} keys.
 *
 * <p>There is deliberately no URL here in the ordinary case. Keycloak's Admin REST API for a
 * realm sits at {@code {base}/admin/realms/{realm}}, and the issuer this application already
 * validates tokens against is {@code {base}/realms/{realm}} -- the same two facts, written
 * twice. {@link KeycloakAdminClient} therefore derives the admin URI from the issuer, so a
 * deployment still points at a different Keycloak in exactly one place, and the endpoint the
 * users are administered through cannot drift from the one their tokens are checked against.
 *
 * <p>{@link #realmUri} exists for the deployment that breaks that assumption -- a reverse proxy
 * that exposes the admin API on another host, say. Setting it wins over the derivation.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.admin")
public class AdminProperties {

    /**
     * Optional explicit Admin REST API root for the realm, e.g.
     * {@code http://localhost:8180/admin/realms/stockmarket}. Left unset, it is derived from
     * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}.
     */
    private String realmUri;

    /**
     * How long a service-account token is reused before a new one is requested. Subtracted from
     * the token's own lifetime, so the margin covers a request that starts just before expiry.
     */
    private Duration tokenRefreshMargin = Duration.ofSeconds(30);

    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);
}
