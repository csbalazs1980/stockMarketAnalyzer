package com.stockmarketanalyzer.backend.security;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Development filter chain: no token, no login, no Keycloak.
 *
 * <p>Active <strong>only</strong> under the {@code local} profile, and {@link SecurityConfig}
 * is {@code @Profile("!local")}, so exactly one of the two is ever in play. Security is opt-out
 * rather than opt-in: a deployment that sets no profile gets the real, locked-down chain.
 *
 * <p>Every request is permitted, and {@link LocalUserAuthenticationFilter} still attaches a
 * synthetic identity so that code reading the caller (wealth ownership, {@code hasRole}) behaves
 * as it does in production. Authorisation rules are deliberately <em>not</em> duplicated here --
 * a second copy of the rule list would drift from the real one, and the local user holds every
 * role anyway.
 */
@Slf4j
@Configuration
@Profile("local")
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@EnableConfigurationProperties(LocalSecurityProperties.class)
public class LocalSecurityConfig {

    private final CorsConfigurationSource corsConfigurationSource;
    private final LocalSecurityProperties properties;

    @Bean
    public SecurityFilterChain localSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()
                        .anyRequest()
                        .permitAll())
                // Before AuthorizationFilter so the identity is on the context by the time
                // anything asks who is calling.
                .addFilterBefore(new LocalUserAuthenticationFilter(properties), AuthorizationFilter.class)
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable());

        return http.build();
    }

    @PostConstruct
    void announce() {
        log.warn(
                """

                ================================================================
                  API SECURITY IS OFF -- 'local' profile is active.
                  Every request is served as '{}' with roles {}, sub {}.
                  No token is required, and any token sent is ignored.
                  Run without the 'local' profile for the real Keycloak chain.
                ================================================================
                """,
                properties.getUsername(),
                properties.getRoles(),
                properties.getSubject());
    }
}
