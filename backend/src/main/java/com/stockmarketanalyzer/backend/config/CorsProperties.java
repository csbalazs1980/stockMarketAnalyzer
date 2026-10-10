package com.stockmarketanalyzer.backend.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from the {@code app.cors.*} keys in application.yaml.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    /** Path patterns the CORS rules apply to (the API itself plus the published contract). */
    private List<String> mappings = List.of("/api/**", "/v3/api-docs/**");

    /** Origins allowed to call the API (supports patterns such as http://localhost:*). */
    private List<String> allowedOriginPatterns = List.of("http://localhost:*");

    /** HTTP methods allowed for cross-origin requests. */
    private List<String> allowedMethods = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    /** Request headers a browser may send. Authorization must be present for bearer tokens. */
    private List<String> allowedHeaders = List.of("*");

    /** Response headers a browser is allowed to read. */
    private List<String> exposedHeaders = List.of("Location", "Link");

    /** Whether credentials (cookies, Authorization header on some clients) may be sent. */
    private boolean allowCredentials = true;

    /** How long a browser may cache the preflight response. */
    private long maxAgeSeconds = 3600;
}
