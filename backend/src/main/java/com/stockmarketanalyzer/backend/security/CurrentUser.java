package com.stockmarketanalyzer.backend.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Resolves the caller's stable Keycloak identity.
 *
 * <p>{@code Authentication#getName()} is deliberately <em>not</em> used: {@code SecurityConfig}
 * sets the principal claim to {@code preferred_username}, which is mutable in Keycloak and can
 * be reassigned to a different person. Ownership is keyed on {@code sub}, which is not.
 */
@Component
public class CurrentUser {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    /**
     * @throws IllegalStateException when there is no JWT on the context -- a programming error,
     *     since every route that calls this is behind {@code authenticated()}.
     */
    public String requireSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            Jwt jwt = jwtAuthentication.getToken();
            String subject = jwt.getSubject();
            if (subject != null && !subject.isBlank()) {
                return subject;
            }
            throw new IllegalStateException("Authenticated token carries no 'sub' claim");
        }
        throw new IllegalStateException("No authenticated JWT on the security context");
    }

    /**
     * Whether the caller holds the application's ADMIN role.
     *
     * <p>Needed because not every privilege can be a path rule. {@code /api/v1/expenses/**} is
     * {@code authenticated()} for all methods -- an ordinary {@code USER} writes their own rows --
     * so "only an administrator may create a <em>global</em> expense category" is a property of the
     * payload rather than of the URL, and only the service can see it.
     *
     * <p>Reads {@code ROLE_ADMIN} off the authorities rather than the token's claims, so it agrees
     * with {@code hasRole("ADMIN")} by construction: both see whatever
     * {@code KeycloakRealmRoleConverter} produced, including under the {@code local} profile where
     * the roles come from configuration instead of a realm.
     */
    public boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> ROLE_ADMIN.equals(authority.getAuthority()));
    }
}
