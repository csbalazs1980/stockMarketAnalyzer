package com.stockmarketanalyzer.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Assembles the OpenAPI document served at {@code /v3/api-docs} and rendered by Swagger UI
 * at {@code /swagger-ui.html}.
 *
 * <p>The OAuth2 security scheme drives the <em>Authorize</em> button: Swagger UI runs the
 * authorization-code + PKCE flow against Keycloak, then attaches the resulting bearer token
 * to every try-it-out request. The endpoint URLs are derived from the same issuer URI the
 * resource server validates tokens against, so the two can never drift apart.
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(OpenApiProperties.class)
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "keycloak";

    private final OpenApiProperties properties;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Bean
    public OpenAPI stockMarketAnalyzerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title(properties.getTitle())
                        .version(properties.getVersion())
                        .description(properties.getDescription())
                        .contact(contact())
                        .license(new License()
                                .name(properties.getLicenseName())
                                .url(properties.getLicenseUrl())))
                .tags(List.of(
                        new Tag().name("Instruments").description("Reference data for tradable instruments."),
                        new Tag().name("Prices").description("Daily OHLCV bars for a given instrument.")))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME_NAME, keycloakScheme()))
                // Applied to every operation; the few public paths are handled by Spring
                // Security, not by the contract.
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }

    /**
     * Restricts the published document to the versioned API, keeping actuator and error
     * endpoints out of the contract.
     */
    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder().group("v1").pathsToMatch("/api/v1/**").build();
    }

    private SecurityScheme keycloakScheme() {
        String base = issuerUri.endsWith("/") ? issuerUri.substring(0, issuerUri.length() - 1) : issuerUri;
        OAuthFlow authorizationCode = new OAuthFlow()
                .authorizationUrl(base + "/protocol/openid-connect/auth")
                .tokenUrl(base + "/protocol/openid-connect/token")
                .refreshUrl(base + "/protocol/openid-connect/token")
                .scopes(new Scopes()
                        .addString("openid", "OpenID Connect sign-in")
                        .addString("profile", "Basic profile claims")
                        .addString("email", "Email address"));

        return new SecurityScheme()
                .type(SecurityScheme.Type.OAUTH2)
                .description("Keycloak realm '%s'. Reads need any authenticated user; writes need the ADMIN role."
                        .formatted(realmName(base)))
                .flows(new OAuthFlows().authorizationCode(authorizationCode));
    }

    private static String realmName(String issuer) {
        int lastSlash = issuer.lastIndexOf('/');
        return lastSlash >= 0 ? issuer.substring(lastSlash + 1) : issuer;
    }

    private Contact contact() {
        Contact contact = new Contact().name(properties.getContactName());
        if (StringUtils.hasText(properties.getContactEmail())) {
            contact.setEmail(properties.getContactEmail());
        }
        return contact;
    }
}
