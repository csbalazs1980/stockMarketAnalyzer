package com.stockmarketanalyzer.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates every request as a fixed local user, without a token and without Keycloak.
 *
 * <p>Why a synthetic {@link Jwt} rather than plain {@code permitAll} with no principal: the
 * application reads the caller's identity in real code paths -- {@code CurrentUser} keys wealth
 * rows on the {@code sub} claim, and {@code hasRole} reads the mapped realm roles. An
 * unauthenticated request would blow up in the first and silently change behaviour in the
 * second, so local development would stop resembling production exactly where it matters.
 * Producing a token-shaped identity keeps every layer below the filter chain identical.
 *
 * <p>The JWT is minted here and never verified: no decoder, no signature, no expiry check.
 * A real {@code Authorization} header on a local request is ignored rather than validated --
 * to exercise genuine Keycloak roles, run without the {@code local} profile.
 *
 * <p>Registered only by {@link LocalSecurityConfig}, which is {@code @Profile("local")}.
 */
public class LocalUserAuthenticationFilter extends OncePerRequestFilter {

    private final Jwt token;
    private final Collection<GrantedAuthority> authorities;
    private final String principalName;

    public LocalUserAuthenticationFilter(LocalSecurityProperties properties) {
        this.token = syntheticToken(properties);
        this.authorities = new KeycloakRealmRoleConverter().convert(this.token);
        this.principalName = properties.getUsername();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(token, authorities, principalName));
        SecurityContextHolder.setContext(context);
        filterChain.doFilter(request, response);
    }

    private static Jwt syntheticToken(LocalSecurityProperties properties) {
        Instant issuedAt = Instant.now();
        return Jwt.withTokenValue("local-development-token")
                .header("alg", "none")
                .subject(properties.getSubject())
                .claim("preferred_username", properties.getUsername())
                .claim("realm_access", Map.of("roles", properties.getRoles()))
                .issuedAt(issuedAt)
                // Nothing validates this; a long horizon just avoids a confusing value in logs.
                .expiresAt(issuedAt.plus(365, ChronoUnit.DAYS))
                .build();
    }
}
