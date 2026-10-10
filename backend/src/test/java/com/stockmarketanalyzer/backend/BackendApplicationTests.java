package com.stockmarketanalyzer.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class BackendApplicationTests extends AbstractPostgresIntegrationTest {

    @Test
    void contextLoadsAndMigrationsApply() {
        // Failing Liquibase changesets or a Hibernate/schema mismatch fail this test.
    }
}
