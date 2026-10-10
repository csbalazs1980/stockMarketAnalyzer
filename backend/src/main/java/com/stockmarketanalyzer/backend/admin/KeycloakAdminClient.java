package com.stockmarketanalyzer.backend.admin;

import com.stockmarketanalyzer.backend.auth.AuthProperties;
import com.stockmarketanalyzer.backend.common.ResourceConflictException;
import com.stockmarketanalyzer.backend.common.ResourceNotFoundException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The only thing in this application that speaks Keycloak's Admin REST API.
 *
 * <p>It authenticates as the <em>service account</em> of the confidential {@code stockmarket-api}
 * client -- a client-credentials grant, not the administrator's own token. That choice is what
 * keeps the two privilege systems apart: being an {@code ADMIN} in this application is what
 * authorises the request (see {@code SecurityConfig} and {@code UserAdminController}), and the
 * ability to reach Keycloak at all belongs to the server. A human administrator therefore needs
 * no {@code realm-management} roles of their own and cannot use their token anywhere near the
 * Keycloak console.
 *
 * <p>The service account needs {@code manage-users}, {@code view-users}, {@code query-users} and
 * {@code view-realm} from the {@code realm-management} client. The first is what permits realm
 * role mapping -- Keycloak's {@code canMapRole} takes {@code manage-users} as sufficient for any
 * role that is not itself one of Keycloak's built-in admin roles -- and {@code view-realm} is
 * what permits reading the realm's role list to offer as choices.
 *
 * <p>Absent under the {@code local} profile, where there is no Keycloak at all.
 *
 * <p>Everything here is expressed in maps and lists rather than mirrored representation classes.
 * Keycloak's {@code UserRepresentation} has upwards of thirty fields, this application touches
 * eight of them, and the ones it does not touch must survive an update untouched -- which a
 * partial mirror would silently erase on every write.
 */
@Slf4j
@Component
@Profile("!local")
public class KeycloakAdminClient {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_ARRAY =
            new ParameterizedTypeReference<>() {};

    /** {@code /users/count} answers with a bare JSON number, not an object. */
    private static final ParameterizedTypeReference<Long> JSON_LONG = new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final AuthProperties auth;
    private final AdminProperties properties;
    private final String realmUri;

    /** Guards the cached service-account token; every call reads or replaces it. */
    private final Object tokenLock = new Object();

    private String cachedToken;
    private Instant cachedUntil = Instant.EPOCH;

    public KeycloakAdminClient(
            @Qualifier("adminRestClient") RestClient restClient,
            AuthProperties auth,
            AdminProperties properties,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
        this.restClient = restClient;
        this.auth = auth;
        this.properties = properties;
        this.realmUri = resolveRealmUri(properties.getRealmUri(), issuerUri);
        log.info("Keycloak Admin API for user management: {}", this.realmUri);
    }

    /**
     * Turns {@code {base}/realms/{realm}} into {@code {base}/admin/realms/{realm}}.
     *
     * <p>Derived rather than configured so that the server whose tokens are accepted and the
     * server whose users are administered are the same server by construction. An explicit
     * {@code app.admin.realm-uri} wins, for the deployment that genuinely splits them.
     */
    static String resolveRealmUri(String configured, String issuerUri) {
        if (configured != null && !configured.isBlank()) {
            return trimTrailingSlash(configured);
        }
        String issuer = trimTrailingSlash(issuerUri);
        int marker = issuer.lastIndexOf("/realms/");
        if (marker < 0) {
            throw new IllegalStateException(
                    "Cannot derive the Keycloak Admin API URI from issuer '%s': it does not contain '/realms/'. "
                            .formatted(issuerUri) + "Set app.admin.realm-uri explicitly.");
        }
        return issuer.substring(0, marker) + "/admin" + issuer.substring(marker);
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    // ---------------------------------------------------------------- users

    /** A page of users, newest Keycloak ordering, optionally narrowed by its {@code search}. */
    public List<Map<String, Object>> searchUsers(String search, int first, int max) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(realmUri + "/users")
                .queryParam("first", first)
                .queryParam("max", max)
                .queryParam("briefRepresentation", true);
        if (search != null && !search.isBlank()) {
            uri.queryParam("search", search.trim());
        }
        List<Map<String, Object>> users = exchange(HttpMethod.GET, uri.build().toUri(), null, JSON_ARRAY);
        return users != null ? users : List.of();
    }

    /**
     * How many users the same {@code search} matches. A separate round trip because Keycloak has
     * no total on the list endpoint, and guessing one from a short page would make the paginator
     * lie on the last page.
     */
    public long countUsers(String search) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(realmUri + "/users/count");
        if (search != null && !search.isBlank()) {
            uri.queryParam("search", search.trim());
        }
        Long count = exchange(HttpMethod.GET, uri.build().toUri(), null, JSON_LONG);
        return count != null ? count : 0L;
    }

    public Map<String, Object> getUser(String id) {
        Map<String, Object> user = exchange(HttpMethod.GET, uri("/users/" + id), null, JSON_OBJECT);
        if (user == null) {
            throw new ResourceNotFoundException("No user with id " + id);
        }
        return user;
    }

    /**
     * Creates the user and returns the id Keycloak assigned, read from the {@code Location}
     * header -- the response body is empty.
     *
     * <p>The password travels in the same representation as an inline credential rather than as a
     * follow-up {@code reset-password} call. One request means there is no window in which the
     * account exists with no way to sign in to it.
     */
    public String createUser(Map<String, Object> representation) {
        ResponseEntity<Void> response =
                exchangeEntity(HttpMethod.POST, uri("/users"), representation);
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new UserDirectoryUnavailableException(
                    "Keycloak created the user but did not say which one; no Location header", null);
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Merges fields into an existing user.
     *
     * <p>Keycloak treats a {@code UserRepresentation} on {@code PUT} as a partial update: absent
     * fields are left alone. That is why callers here send two or three keys rather than a
     * round-tripped whole user -- a full representation would carry back whatever this
     * application failed to understand on the way out.
     */
    public void updateUser(String id, Map<String, Object> representation) {
        exchangeEntity(HttpMethod.PUT, uri("/users/" + id), representation);
    }

    public void resetPassword(String id, String password, boolean temporary) {
        exchangeEntity(
                HttpMethod.PUT,
                uri("/users/" + id + "/reset-password"),
                Map.of("type", "password", "value", password, "temporary", temporary));
    }

    // ---------------------------------------------------------------- roles

    /** Every realm role defined in the realm, as brief representations. */
    public List<Map<String, Object>> realmRoles() {
        URI uri = UriComponentsBuilder.fromUriString(realmUri + "/roles")
                .queryParam("briefRepresentation", true)
                .build()
                .toUri();
        List<Map<String, Object>> roles = exchange(HttpMethod.GET, uri, null, JSON_ARRAY);
        return roles != null ? roles : List.of();
    }

    /** The realm roles mapped directly to one user (not the ones inherited via composites). */
    public List<Map<String, Object>> userRealmRoles(String id) {
        List<Map<String, Object>> roles =
                exchange(HttpMethod.GET, uri("/users/" + id + "/role-mappings/realm"), null, JSON_ARRAY);
        return roles != null ? roles : List.of();
    }

    public void addRealmRoles(String id, List<Map<String, Object>> roles) {
        if (roles.isEmpty()) {
            return;
        }
        exchangeEntity(HttpMethod.POST, uri("/users/" + id + "/role-mappings/realm"), roles);
    }

    /**
     * Keycloak un-maps roles with a {@code DELETE} that carries a body. Unusual, and the reason
     * this class drives {@link RestClient#method(HttpMethod)} by hand instead of the
     * {@code delete()} shortcut, which has no {@code body}.
     */
    public void removeRealmRoles(String id, List<Map<String, Object>> roles) {
        if (roles.isEmpty()) {
            return;
        }
        exchangeEntity(HttpMethod.DELETE, uri("/users/" + id + "/role-mappings/realm"), roles);
    }

    // ---------------------------------------------------------------- plumbing

    private URI uri(String path) {
        return URI.create(realmUri + path);
    }

    private <T> T exchange(HttpMethod method, URI uri, Object body, ParameterizedTypeReference<T> type) {
        return send(method, uri, body, spec -> spec.retrieve().body(type));
    }

    private ResponseEntity<Void> exchangeEntity(HttpMethod method, URI uri, Object body) {
        return send(method, uri, body, spec -> spec.retrieve().toBodilessEntity());
    }

    /**
     * Runs the request with a service-account token, and retries it exactly once -- on a 401 or a
     * 403 -- with a freshly minted one.
     *
     * <p>The retry is not belt-and-braces. A cached token can be perfectly valid by the clock and
     * still be refused, because an administrator restarted Keycloak, reimported the realm or
     * revoked the session; without the retry every request would fail until the cache aged out on
     * its own, for up to the token's whole lifetime.
     *
     * <p><strong>403 is retried for a subtler reason.</strong> A token carries the roles the
     * account had when it was minted. Granting the service account its {@code realm-management}
     * roles therefore changes nothing for a token already in this cache -- so the first thing an
     * administrator does after fixing the deployment is watch it go on failing for another half
     * hour, with an error message telling them to do the thing they have just done. Re-minting
     * once is what makes the fix take effect immediately.
     */
    private <T> T send(
            HttpMethod method,
            URI uri,
            Object body,
            java.util.function.Function<RestClient.RequestBodySpec, T> call) {
        try {
            return call.apply(request(method, uri, body, accessToken(false)));
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
                log.debug("Keycloak answered {} to the cached service-account token; minting a new one", status);
                try {
                    return call.apply(request(method, uri, body, accessToken(true)));
                } catch (RestClientResponseException retryFailure) {
                    throw translate(retryFailure, method, uri);
                } catch (RestClientException retryFailure) {
                    throw unreachable(retryFailure);
                }
            }
            throw translate(ex, method, uri);
        } catch (RestClientException ex) {
            throw unreachable(ex);
        }
    }

    private RestClient.RequestBodySpec request(HttpMethod method, URI uri, Object body, String token) {
        RestClient.RequestBodySpec spec = restClient
                .method(method)
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec;
    }

    /**
     * The service account's own access token, cached until shortly before it expires.
     *
     * <p>Synchronised rather than clever: minting a token is a network round trip, this is an
     * administration screen used by one person at a time, and two threads racing to replace the
     * cache would simply spend two tokens for no gain.
     */
    private String accessToken(boolean force) {
        synchronized (tokenLock) {
            if (!force && cachedToken != null && Instant.now().isBefore(cachedUntil)) {
                return cachedToken;
            }
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", auth.getClientId());
            if (auth.getClientSecret() != null && !auth.getClientSecret().isBlank()) {
                form.add("client_secret", auth.getClientSecret());
            }

            Map<String, Object> response;
            try {
                response = restClient
                        .post()
                        .uri(auth.getTokenUri())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(JSON_OBJECT);
            } catch (RestClientResponseException ex) {
                log.warn("Keycloak refused the service-account credentials: {} {}", ex.getStatusCode(),
                        ex.getResponseBodyAsString());
                throw new UserDirectoryUnavailableException(
                        "The identity provider refused this application's service account. Check that the "
                                + "app.auth.client-id client has service accounts enabled and holds the "
                                + "realm-management roles manage-users, view-users, query-users and view-realm.",
                        ex);
            } catch (RestClientException ex) {
                throw unreachable(ex);
            }

            String token = response == null ? null : asText(response.get("access_token"));
            if (token == null) {
                throw new UserDirectoryUnavailableException(
                        "The identity provider returned no service-account token", null);
            }

            cachedToken = token;
            cachedUntil = Instant.now().plus(lifetime(response).minus(properties.getTokenRefreshMargin()));
            return cachedToken;
        }
    }

    private static Duration lifetime(Map<String, Object> response) {
        Object expiresIn = response.get("expires_in");
        long seconds = expiresIn instanceof Number number ? number.longValue() : 0L;
        // A margin larger than the lifetime would make cachedUntil be in the past, which simply
        // means "mint one every time" -- correct, if wasteful, so it needs no special case.
        return Duration.ofSeconds(Math.max(0L, seconds));
    }

    private static String asText(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /**
     * Maps Keycloak's answer onto this application's own vocabulary.
     *
     * <p>409 and 404 are the caller's problem and are reported as such -- a duplicate username is
     * something an administrator can fix by typing a different one. Everything else is this
     * application's problem with Keycloak, and a 403 in particular means the service account is
     * missing a role, which is a deployment fault and must not read as "you may not do that".
     */
    private RuntimeException translate(RestClientResponseException ex, HttpMethod method, URI uri) {
        int status = ex.getStatusCode().value();
        String message = keycloakMessage(ex);

        if (status == HttpStatus.CONFLICT.value()) {
            return new ResourceConflictException(
                    message != null ? message : "That username or email address is already taken.");
        }
        if (status == HttpStatus.NOT_FOUND.value()) {
            return new ResourceNotFoundException(message != null ? message : "No such user in the realm.");
        }
        if (status == HttpStatus.BAD_REQUEST.value()) {
            return new IllegalArgumentException(
                    message != null ? message : "The identity provider rejected this change.");
        }

        log.warn("Keycloak Admin API said {} to {} {}: {}", status, method, uri.getPath(),
                ex.getResponseBodyAsString());
        if (status == HttpStatus.FORBIDDEN.value()) {
            return new UserDirectoryUnavailableException(
                    "This application's service account is not allowed to manage users. It needs the "
                            + "realm-management roles manage-users, view-users, query-users and view-realm.",
                    ex);
        }
        return new UserDirectoryUnavailableException(
                "The identity provider answered with " + status + " while managing users.", ex);
    }

    /**
     * Keycloak's own explanation, which is usually the useful one ("User exists with same
     * username"). Read out of the body by hand rather than bound to a class, to stay clear of the
     * Jackson 2 vs 3 question Boot 4 leaves open -- and to tolerate the several shapes Keycloak
     * uses ({@code errorMessage}, {@code error_description}, {@code error}).
     */
    private static String keycloakMessage(RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return null;
        }
        for (String key : new String[] {"errorMessage", "error_description", "error"}) {
            String needle = "\"" + key + "\"";
            int at = body.indexOf(needle);
            if (at < 0) {
                continue;
            }
            int colon = body.indexOf(':', at + needle.length());
            int open = colon < 0 ? -1 : body.indexOf('"', colon);
            int close = open < 0 ? -1 : body.indexOf('"', open + 1);
            if (close > open) {
                String value = body.substring(open + 1, close).trim();
                if (!value.isEmpty() && !"unknown_error".equals(value.toLowerCase(Locale.ROOT))) {
                    return value;
                }
            }
        }
        return null;
    }

    private static UserDirectoryUnavailableException unreachable(RestClientException ex) {
        return new UserDirectoryUnavailableException(
                "Could not reach the identity provider: " + ex.getMessage(), ex);
    }
}
