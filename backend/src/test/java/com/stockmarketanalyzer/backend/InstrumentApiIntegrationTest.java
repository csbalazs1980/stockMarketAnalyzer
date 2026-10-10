package com.stockmarketanalyzer.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockmarketanalyzer.backend.instrument.domain.AssetType;
import com.stockmarketanalyzer.backend.instrument.web.dto.InstrumentOption;
import com.stockmarketanalyzer.backend.instrument.web.dto.InstrumentRequest;
import com.stockmarketanalyzer.backend.instrument.web.dto.InstrumentResponse;
import com.stockmarketanalyzer.backend.price.web.dto.PriceBarRequest;
import com.stockmarketanalyzer.backend.price.web.dto.PriceBarResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InstrumentApiIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createsInstrumentAndStoresPriceBars() {
        InstrumentRequest request =
                new InstrumentRequest("tsla", "Tesla, Inc.", "NASDAQ", "usd", AssetType.STOCK, "Automotive", true);

        ResponseEntity<InstrumentResponse> created = restTemplate.exchange(
                "/api/v1/instruments",
                HttpMethod.POST,
                new HttpEntity<>(request, TestSecurityConfiguration.adminHeaders()),
                InstrumentResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().symbol()).isEqualTo("TSLA");
        assertThat(created.getBody().currency()).isEqualTo("USD");

        List<PriceBarRequest> bars = List.of(new PriceBarRequest(
                LocalDate.of(2026, 1, 5),
                new BigDecimal("240.10"),
                new BigDecimal("249.90"),
                new BigDecimal("238.00"),
                new BigDecimal("247.25"),
                null,
                85_000_000L));

        ResponseEntity<PriceBarResponse[]> stored = restTemplate.exchange(
                "/api/v1/instruments/TSLA/prices",
                HttpMethod.POST,
                new HttpEntity<>(bars, TestSecurityConfiguration.adminHeaders()),
                PriceBarResponse[].class);

        assertThat(stored.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(stored.getBody()).hasSize(1);

        ResponseEntity<PriceBarResponse> latest = restTemplate.exchange(
                "/api/v1/instruments/TSLA/prices/latest",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                PriceBarResponse.class);

        assertThat(latest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(latest.getBody()).isNotNull();
        assertThat(latest.getBody().close()).isEqualByComparingTo("247.25");
    }

    /**
     * The regression behind the endpoint: the picker used to ask for one page of 500 and Visa,
     * ~1780 tickers into the alphabet, simply was not in the answer. Liquibase seeds the NYSE
     * universe in the default context, so the assertion has real data to bite on.
     */
    @Test
    void optionsReturnTheWholeUniverseInOneUnpagedResponse() {
        ResponseEntity<InstrumentOption[]> response = restTemplate.exchange(
                "/api/v1/instruments/options?active=true",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                InstrumentOption[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        List<InstrumentOption> options = List.of(response.getBody());
        assertThat(options).hasSizeGreaterThan(1_000);
        assertThat(options)
                .filteredOn(option -> "V".equals(option.symbol()))
                .singleElement()
                .satisfies(visa -> {
                    assertThat(visa.name()).isEqualTo("Visa Inc.");
                    assertThat(visa.currency()).isEqualTo("USD");
                    assertThat(visa.id()).isNotNull();
                });
    }

    @Test
    void returnsProblemDetailForUnknownSymbol() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/instruments/DOES-NOT-EXIST",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("No instrument found");
    }
}
