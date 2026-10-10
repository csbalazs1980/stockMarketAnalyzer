package com.stockmarketanalyzer.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockmarketanalyzer.backend.instrument.domain.AssetType;
import com.stockmarketanalyzer.backend.instrument.web.dto.InstrumentRequest;
import com.stockmarketanalyzer.backend.instrument.web.dto.InstrumentResponse;
import com.stockmarketanalyzer.backend.wealth.web.dto.WealthEntryRequest;
import com.stockmarketanalyzer.backend.wealth.web.dto.WealthEntryResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Pins the two things the {@code local} profile promises: writes work with no token at all,
 * and the caller still has an identity, so the code that reads {@code sub} keeps working.
 *
 * <p>The second half is the one worth guarding. A `permitAll` chain with no principal passes a
 * smoke test and then fails on every wealth endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class LocalProfileSecurityIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void servesWritesWithoutATokenAndStillAttributesThemToSomeone() {
        WealthEntryRequest request =
                new WealthEntryRequest("kp", "HUF", new BigDecimal("1550000"), LocalDate.of(2026, 8, 5), null);

        // No Authorization header anywhere in this test -- that is the point.
        ResponseEntity<WealthEntryResponse> created = restTemplate.exchange(
                "/api/v1/wealth/entries", HttpMethod.POST, new HttpEntity<>(request), WealthEntryResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().name()).isEqualTo("kp");

        // Reading it back proves the row was owned by a stable subject rather than rejected
        // or orphaned: the query filters on the caller's sub.
        ResponseEntity<WealthEntryResponse[]> latest = restTemplate.exchange(
                "/api/v1/wealth/entries/latest", HttpMethod.GET, HttpEntity.EMPTY, WealthEntryResponse[].class);

        assertThat(latest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(latest.getBody()).extracting(WealthEntryResponse::name).contains("kp");
    }

    @Test
    void allowsAnAdminOnlyWriteWithoutACredential() {
        // Creating an instrument needs the ADMIN realm role in production. Locally it succeeds
        // unauthenticated, because the synthetic identity carries that role.
        InstrumentRequest request =
                new InstrumentRequest("nvda", "NVIDIA Corporation", "NASDAQ", "usd", AssetType.STOCK, "Technology", true);

        ResponseEntity<InstrumentResponse> created = restTemplate.exchange(
                "/api/v1/instruments", HttpMethod.POST, new HttpEntity<>(request), InstrumentResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().symbol()).isEqualTo("NVDA");
    }
}
