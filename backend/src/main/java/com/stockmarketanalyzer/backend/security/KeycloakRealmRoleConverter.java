package com.stockmarketanalyzer.backend.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Turns a Keycloak access token into Spring Security authorities.
 *
 * <p>Keycloak publishes realm roles under {@code realm_access.roles}, which Spring Security
 * knows nothing about — without this converter every token authenticates with no roles at
 * all and every {@code hasRole} check fails. Roles are prefixed with {@code ROLE_} because
 * that is what {@code hasRole("ADMIN")} looks for; OAuth2 scopes are still mapped as
 * {@code SCOPE_*} by the default converter.
 */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String ROLES_KEY = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtGrantedAuthoritiesConverter scopesConverter = new JwtGrantedAuthoritiesConverter();

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new LinkedHashSet<>();

        Collection<GrantedAuthority> scopes = scopesConverter.convert(jwt);
        if (scopes != null) {
            authorities.addAll(scopes);
        }
        for (String role : realmRoles(jwt)) {
            authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + role));
        }
        return authorities;
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
}
