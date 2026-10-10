package com.stockmarketanalyzer.backend.auth;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from the {@code app.auth.*} keys.
 *
 * <p>The token and logout URIs are spelled out in {@code application.yaml} rather than derived
 * from {@code issuer-uri} in code, so that the one place a deployment points at a different
 * Keycloak stays the one place: the environment variable both properties interpolate.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /** Keycloak's OIDC token endpoint. */
    private String tokenUri;

    /** Keycloak's OIDC end-session endpoint; used to revoke a refresh token on sign-out. */
    private String logoutUri;

    /**
     * The client the backend authenticates as. It must be <em>confidential</em> and have direct
     * access grants enabled, and it must carry the audience mapper for {@code stockmarket-api} --
     * otherwise the access tokens it mints are rejected by this very application's
     * {@code jwt.audiences} check.
     */
    private String clientId = "stockmarket-api";

    /** Never leaves the server. This is the whole reason the SPA cannot talk to Keycloak itself. */
    private String clientSecret;

    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);

    private final Cookie cookie = new Cookie();

    /**
     * Both tokens travel as {@code HttpOnly} cookies, so no script on the page can read them --
     * which is the point of proxying the login in the first place. The browser attaches them on
     * its own; nothing in the frontend ever holds a token.
     */
    @Getter
    @Setter
    public static class Cookie {

        /** Carries the access token. Sent with every API call, hence the wide path. */
        private String accessName = "sma_at";

        /** Carries the refresh token. Scoped to the auth endpoints -- nothing else needs it. */
        private String refreshName = "sma_rt";

        private String path = "/";
        private String refreshPath = "/api/v1/auth";

        /**
         * Must be {@code true} anywhere that is not plain-HTTP localhost, and is required by
         * browsers when {@code sameSite} is {@code None}.
         */
        private boolean secure = false;

        /**
         * {@code Lax} works for the dev setup (4200 and 8080 are different origins but the same
         * site) and for a same-origin production deployment. A frontend served from a genuinely
         * different site needs {@code None} plus {@code secure: true}.
         */
        private String sameSite = "Lax";
    }
}
