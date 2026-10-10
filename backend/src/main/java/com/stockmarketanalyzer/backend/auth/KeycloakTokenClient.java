package com.stockmarketanalyzer.backend.auth;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The only thing in this application that speaks OAuth2 to Keycloak.
 *
 * <p>It exists so that the browser does not have to. The SPA posts a username and a password to
 * this application; this class exchanges them for tokens using the <em>confidential</em>
 * {@code stockmarket-api} client, whose secret never leaves the server. No Keycloak URL, no
 * redirect and no client secret is ever visible to the user.
 *
 * <p>Absent under the {@code local} profile, where there is no Keycloak to call and
 * {@code LocalUserAuthenticationFilter} has already authenticated every request.
 */
@Slf4j
@Component
@Profile("!local")
public class KeycloakTokenClient {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    /**
     * The OAuth2 error codes that mean "the credential is no good", as opposed to "the request
     * or the server is broken". Keycloak returns {@code invalid_grant} for a wrong password, an
     * unknown user, a disabled account and an expired refresh token alike.
     *
     * <p>{@code unauthorized_client} is deliberately <em>not</em> here. It means direct access
     * grants are switched off on the client, or the secret is wrong -- a deployment fault that
     * must not reach the user as "check your password", which is advice they cannot act on.
     */
    private static final String[] CREDENTIAL_ERRORS = {"invalid_grant", "invalid_user_credentials"};

    private final RestClient restClient;
    private final AuthProperties properties;

    public KeycloakTokenClient(@Qualifier("authRestClient") RestClient restClient, AuthProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /** Exchanges a username and password for a token set (OAuth2 direct access grant). */
    public TokenSet passwordGrant(String username, String password) {
        MultiValueMap<String, String> form = clientForm();
        form.add("grant_type", "password");
        form.add("username", username);
        form.add("password", password);
        // openid is what makes Keycloak treat this as an OIDC login and populate the standard
        // profile claims the session response reports back to the SPA.
        form.add("scope", "openid profile email");
        return token(form);
    }

    /** Exchanges a refresh token for a fresh token set. Keycloak rotates the refresh token too. */
    public TokenSet refreshGrant(String refreshToken) {
        MultiValueMap<String, String> form = clientForm();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        return token(form);
    }

    /**
     * Ends the Keycloak session behind a refresh token.
     *
     * <p>Failures are logged and swallowed on purpose: the cookies are cleared by the caller
     * either way, and a sign-out that reports an error because the identity provider was
     * briefly unreachable leaves the user staring at a page they have already left.
     */
    public void revoke(String refreshToken) {
        MultiValueMap<String, String> form = clientForm();
        form.add("refresh_token", refreshToken);
        try {
            restClient
                    .post()
                    .uri(properties.getLogoutUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            log.warn("Could not revoke the refresh token at Keycloak: {}", ex.getMessage());
        }
    }

    private TokenSet token(MultiValueMap<String, String> form) {
        Map<String, Object> body;
        try {
            body = restClient
                    .post()
                    .uri(properties.getTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JSON_OBJECT);
        } catch (RestClientResponseException ex) {
            throw translate(ex);
        } catch (RestClientException ex) {
            throw new AuthUnavailableException("Could not reach the identity provider: " + ex.getMessage(), ex);
        }

        if (body == null) {
            throw new AuthUnavailableException("The identity provider returned an empty token response", null);
        }

        String accessToken = text(body, "access_token");
        String refreshToken = text(body, "refresh_token");
        if (accessToken == null || refreshToken == null) {
            throw new AuthUnavailableException("The identity provider returned an incomplete token response", null);
        }
        return new TokenSet(
                accessToken, refreshToken, seconds(body, "expires_in"), seconds(body, "refresh_expires_in"));
    }

    /**
     * Splits Keycloak's error responses into "your credential is wrong" (401 to the caller) and
     * everything else (502). The distinction is read from the OAuth2 {@code error} code in the
     * body rather than the status, because Keycloak answers 400 for a bad password on a refresh
     * and 401 for a bad password on a login.
     */
    private RuntimeException translate(RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        String lowered = body == null ? "" : body.toLowerCase(Locale.ROOT);
        for (String code : CREDENTIAL_ERRORS) {
            if (lowered.contains(code)) {
                log.debug("Keycloak rejected a credential: {} {}", ex.getStatusCode(), body);
                return new InvalidCredentialsException("The identity provider rejected the credentials");
            }
        }
        log.warn("Unexpected response from Keycloak: {} {}", ex.getStatusCode(), body);
        return new AuthUnavailableException(
                "The identity provider answered with " + ex.getStatusCode().value(), ex);
    }

    private MultiValueMap<String, String> clientForm() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.getClientId());
        if (properties.getClientSecret() != null && !properties.getClientSecret().isBlank()) {
            form.add("client_secret", properties.getClientSecret());
        }
        return form;
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    /** {@code expires_in} is seconds; a missing or unusable value is treated as "already gone". */
    private static Duration seconds(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value instanceof Number number) {
            return Duration.ofSeconds(Math.max(0L, number.longValue()));
        }
        if (value instanceof String string) {
            try {
                return Duration.ofSeconds(Math.max(0L, Long.parseLong(string.trim())));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return Duration.ZERO;
    }
}
