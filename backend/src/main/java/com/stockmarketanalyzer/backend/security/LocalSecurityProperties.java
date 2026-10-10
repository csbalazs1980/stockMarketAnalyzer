package com.stockmarketanalyzer.backend.security;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from the {@code app.local-security.*} keys. Only read under the {@code local} profile,
 * where {@link LocalSecurityConfig} replaces the real filter chain.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.local-security")
public class LocalSecurityProperties {

    /**
     * Stable identity every local request is attributed to. Wealth rows are owned by the
     * Keycloak {@code sub}, so this has to stay constant or yesterday's data disappears.
     * The value is not a real Keycloak subject; it never has to be.
     */
    private String subject = "00000000-0000-0000-0000-00000000d3v0";

    /** Principal name, i.e. what {@code preferred_username} would carry in a real token. */
    private String username = "local-dev";

    /** Realm roles granted to the synthetic user, mapped to {@code ROLE_*} as in production. */
    private List<String> roles = List.of("ADMIN", "USER");
}
