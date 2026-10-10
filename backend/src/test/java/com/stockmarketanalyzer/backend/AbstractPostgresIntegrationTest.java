package com.stockmarketanalyzer.backend;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots a throwaway PostgreSQL container and points Spring at it, so integration tests
 * exercise the real Liquibase migrations. The whole class is skipped when Docker is
 * unavailable, which keeps {@code ./gradlew build} green on machines without a daemon.
 *
 * <p>{@link TestSecurityConfiguration} supplies a stub JWT decoder — the security rules are
 * real, only Keycloak is not.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestSecurityConfiguration.class)
public abstract class AbstractPostgresIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("stockmarket")
            .withUsername("stockmarket")
            .withPassword("stockmarket")
            .withReuse(false);

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.liquibase.contexts", () -> "default");
        // Never contacted -- the stub decoder replaces the real one -- but the value is read
        // by OpenApiConfig when it builds the Swagger authorization URLs.
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "https://keycloak.test/realms/stockmarket");
    }
}
