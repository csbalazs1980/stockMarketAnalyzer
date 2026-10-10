package com.stockmarketanalyzer.backend.auth;

import com.stockmarketanalyzer.backend.auth.web.dto.AuthSessionResponse;
import com.stockmarketanalyzer.backend.auth.web.dto.LoginRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

/**
 * The login, refresh and logout the frontend sees, expressed in terms of Keycloak underneath.
 *
 * <p>Every method here ends with the tokens in cookies and nothing token-shaped in the response
 * body. The SPA learns who it is signed in as and when to come back; it never learns how the
 * answer was obtained.
 *
 * <p>The freshly minted access token is run through this application's own {@link JwtDecoder}
 * before it is handed out. That is not ceremony: it is the same validation every subsequent API
 * call will apply, so a realm whose client is missing the {@code stockmarket-api} audience
 * mapper fails loudly here, at sign-in, instead of silently 401-ing every later request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String ROLES_KEY = "roles";

    /**
     * Absent under the {@code local} profile. {@link ObjectProvider} rather than
     * {@code @Autowired(required = false)} so the absence is a value this class reasons about
     * instead of a null it has to remember to check.
     */
    private final ObjectProvider<KeycloakTokenClient> keycloak;

    private final ObjectProvider<JwtDecoder> jwtDecoder;
    private final AuthCookies cookies;

    /** Exchanges credentials for a cookie-borne session. */
    public AuthSessionResponse login(LoginRequest request, HttpServletResponse response) {
        KeycloakTokenClient client = keycloak.getIfAvailable();
        if (client == null) {
            return localSession();
        }

        TokenSet tokens = client.passwordGrant(request.username().trim(), request.password());
        cookies.write(response, tokens);
        log.debug("Signed in '{}'", request.username());
        return sessionFromTokens(tokens);
    }

    /** Trades the refresh cookie for a new pair of cookies. Keycloak rotates both tokens. */
    public AuthSessionResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        KeycloakTokenClient client = keycloak.getIfAvailable();
        if (client == null) {
            return localSession();
        }

        String refreshToken = cookies.refreshToken(request)
                .orElseThrow(() -> new InvalidCredentialsException("No refresh token was presented"));
        try {
            TokenSet tokens = client.refreshGrant(refreshToken);
            cookies.write(response, tokens);
            return sessionFromTokens(tokens);
        } catch (InvalidCredentialsException ex) {
            // The session is over -- leaving a dead refresh cookie in place would make every
            // page load retry it.
            cookies.clear(response);
            throw ex;
        }
    }

    /**
     * Ends the session at both ends: the cookies go, and the Keycloak session behind the refresh
     * token is revoked so it cannot be replayed.
     */
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        KeycloakTokenClient client = keycloak.getIfAvailable();
        cookies.refreshToken(request).ifPresent(token -> {
            if (client != null) {
                client.revoke(token);
            }
        });
        // Unconditional, and after the revocation attempt: a sign-out that leaves the cookies
        // behind because Keycloak was unreachable is not a sign-out.
        cookies.clear(response);
    }

    /**
     * Who the caller is on a fresh page load.
     *
     * <p>The refresh fallback is what makes a reload keep the user signed in: the access cookie
     * expires with its token, so half an hour into a session there is nothing to authenticate
     * with until the refresh token is spent. Doing it here rather than in the SPA keeps that to
     * one request.
     */
    public Optional<AuthSessionResponse> session(HttpServletRequest request, HttpServletResponse response) {
        Optional<AuthSessionResponse> authenticated = currentSession();
        if (authenticated.isPresent()) {
            return authenticated;
        }
        if (cookies.refreshToken(request).isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(refresh(request, response));
        } catch (InvalidCredentialsException ex) {
            return Optional.empty();
        } catch (AuthUnavailableException ex) {
            log.warn("Could not restore a session: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** The session as the security context already sees it, if the request was authenticated. */
    public Optional<AuthSessionResponse> currentSession() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return Optional.of(sessionFromJwt(jwtAuthentication.getToken()));
        }
        return Optional.empty();
    }

    private AuthSessionResponse sessionFromTokens(TokenSet tokens) {
        JwtDecoder decoder = jwtDecoder.getIfAvailable();
        if (decoder == null) {
            throw new AuthUnavailableException("No JWT decoder is configured", null);
        }
        try {
            return sessionFromJwt(decoder.decode(tokens.accessToken()));
        } catch (JwtException ex) {
            throw new AuthUnavailableException(
                    "The identity provider issued a token this application will not accept. Check that the "
                            + "app.auth.client-id client carries an audience mapper for the API's audience.",
                    ex);
        }
    }

    private static AuthSessionResponse sessionFromJwt(Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        String name = jwt.getClaimAsString("name");
        return new AuthSessionResponse(
                username,
                name != null && !name.isBlank() ? name : username,
                realmRoles(jwt),
                secondsUntil(jwt.getExpiresAt()));
    }

    /**
     * Under the {@code local} profile there is no Keycloak, and every request already arrives
     * authenticated as the fixed developer identity. Signing in is therefore a question rather
     * than an action: it reports who you have been all along.
     */
    private AuthSessionResponse localSession() {
        return currentSession()
                .orElseThrow(() -> new InvalidCredentialsException(
                        "The 'local' profile is active but no local identity was attached"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim(REALM_ACCESS_CLAIM);
        if (realmAccess == null) {
            return List.of();
        }
        Object roles = realmAccess.get(ROLES_KEY);
        if (roles instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }

    private static long secondsUntil(Instant expiry) {
        if (expiry == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(Instant.now(), expiry).toSeconds());
    }
}
