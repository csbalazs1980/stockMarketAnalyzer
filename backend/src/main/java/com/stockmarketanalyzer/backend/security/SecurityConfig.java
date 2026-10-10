package com.stockmarketanalyzer.backend.security;

import com.stockmarketanalyzer.backend.auth.AuthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Locks the API behind Keycloak-issued JWTs.
 *
 * <p>The service is still a pure resource server: no server-side session, no login page of its
 * own, no local user store. Identity lives entirely in Keycloak and arrives on each request as a
 * bearer token. What changed when the SPA stopped talking to Keycloak directly is only
 * <em>where the token is picked up from</em> -- see {@link CookieBearerTokenResolver} -- and the
 * CSRF protection that using a cookie makes necessary.
 *
 * <p>Disabled under the {@code local} profile, where {@link LocalSecurityConfig} takes over and
 * serves every request as a fixed developer identity. The condition is negative on purpose:
 * anything that is not explicitly local -- including a deployment with no profile set at all --
 * gets this chain.
 */
@Configuration
@Profile("!local")
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * Reachable without a token. The API contract is public on purpose — a client needs to
     * read it before it can authenticate — and the health probe has to answer for Docker.
     */
    private static final String[] PUBLIC_PATHS = {
        "/actuator/health",
        "/actuator/health/**",
        "/actuator/info",
        "/v3/api-docs",
        "/v3/api-docs/**",
        "/v3/api-docs.yaml",
        "/v3/api-docs.yaml/**",
        "/swagger-ui.html",
        "/swagger-ui/**"
    };

    /**
     * The sign-in surface itself. It has to be open: these are the endpoints a caller uses
     * precisely because it has no token yet.
     *
     * <p>{@code permitAll} skips the <em>authorisation</em> check, not authentication -- a
     * request that does carry a valid token cookie still arrives at {@code /session} with an
     * identity on the context, which is exactly how that endpoint answers "who am I".
     */
    private static final String[] AUTH_PATHS = {
        "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/session"
    };

    private final ProblemDetailAuthenticationEntryPoint authenticationEntryPoint;
    private final ProblemDetailAccessDeniedHandler accessDeniedHandler;
    private final CorsConfigurationSource corsConfigurationSource;
    private final AuthProperties authProperties;

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        AuthProperties.Cookie cookies = authProperties.getCookie();

        http.csrf(csrf -> csrf
                        // Spring Security 7's single-page-application preset: the token goes out
                        // in a readable XSRF-TOKEN cookie and is expected back raw in a header,
                        // rather than in the BREACH-masked form only a rendered server-side form
                        // could undo. Doing this by hand is three fiddly pieces that fail closed
                        // and silently -- every write 403s -- so the preset is worth taking.
                        .spa()
                        // Only browser-cookie callers are protected; bearer-token clients are
                        // not vulnerable and are left alone. See CookieCredentialCsrfMatcher.
                        .requireCsrfProtectionMatcher(new CookieCredentialCsrfMatcher(
                                cookies.getAccessName(), cookies.getRefreshName())))
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // Still stateless: the cookies carry Keycloak's tokens, not a server session id,
                // so nothing is held in memory between requests.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()
                        .requestMatchers(PUBLIC_PATHS)
                        .permitAll()
                        // Above the admin-only POST rule below, which would otherwise make
                        // signing in require the role you sign in to obtain.
                        .requestMatchers(AUTH_PATHS)
                        .permitAll()
                        // User administration. Explicit because the method-based rules below
                        // only cover writes: without this, a GET of the user list would fall
                        // through to anyRequest().authenticated() and any signed-in USER could
                        // read the whole realm's directory.
                        .requestMatchers("/api/v1/admin/**")
                        .hasRole("ADMIN")
                        // Wealth rows, exchange rates, received dividends and recurring expenses
                        // belong to the caller, not to the installation, so an ordinary USER writes
                        // their own. These must stay ABOVE the admin-only rules below -- first match
                        // wins. Expenses carry one administrator-only action all the same: creating a
                        // GLOBAL category. That cannot be a path rule, because it is a property of
                        // the payload rather than of the URL, so ExpenseCategoryService checks
                        // CurrentUser.isAdmin() instead.
                        .requestMatchers("/api/v1/wealth/**")
                        .authenticated()
                        .requestMatchers("/api/v1/dividends/**")
                        .authenticated()
                        .requestMatchers("/api/v1/expenses/**")
                        .authenticated()
                        // Mutating operations are admin-only; reads need any valid token.
                        .requestMatchers(HttpMethod.POST, "/api/v1/**")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/**")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/**")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/**")
                        .hasRole("ADMIN")
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(new CookieBearerTokenResolver(cookies.getAccessName()))
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // No basic auth prompt, and no form login: the login page is the SPA's, and it
                // posts to /api/v1/auth/login rather than to a Spring Security filter.
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable());

        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        // Without this the principal name is the opaque Keycloak subject UUID, which makes
        // access logs and audit trails unreadable.
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }
}
