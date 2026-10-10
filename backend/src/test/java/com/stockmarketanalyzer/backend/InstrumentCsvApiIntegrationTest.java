package com.stockmarketanalyzer.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockmarketanalyzer.backend.common.csv.CsvImportResult;
import com.stockmarketanalyzer.backend.dividend.web.dto.DividendRequest;
import com.stockmarketanalyzer.backend.instrument.domain.AssetType;
import com.stockmarketanalyzer.backend.instrument.web.dto.InstrumentRequest;
import com.stockmarketanalyzer.backend.instrument.web.dto.InstrumentResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Instrument administration's CSV round trip and its delete guard.
 *
 * <p>Works on Z-prefixed tickers nothing else in the shared container uses; none of them is in
 * the NYSE seed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InstrumentCsvApiIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String IMPORT = "/api/v1/instruments/import";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createsNewSymbolsUpdatesKnownOnesAndCountsTheRest() {
        CsvImportResult first = importAs(
                TestSecurityConfiguration.adminHeaders(),
                """
                symbol,name,exchange,currency,assetType,sector,active
                zqa,ZQA Holdings,NASDAQ,usd,stock,Technology,true
                ZQB,ZQB Fund,,EUR,ETF,,
                ZQC,Bad type,NYSE,USD,SPACESHIP,,true
                ZQD,Bad currency,NYSE,DOLLARS,STOCK,,true
                """);
        // Lower-case ticker, currency and asset type are normalised; the unknown type and the
        // four-letter currency fail the row, not the file.
        assertThat(first.totalRows()).isEqualTo(4);
        assertThat(first.processedRows()).isEqualTo(2);
        assertThat(first.failedRows()).isEqualTo(2);
        assertThat(first.duplicateRows()).isZero();

        InstrumentResponse zqa = get("ZQA");
        assertThat(zqa.currency()).isEqualTo("USD");
        assertThat(zqa.assetType()).isEqualTo(AssetType.STOCK);
        InstrumentResponse zqb = get("ZQB");
        assertThat(zqb.active()).isTrue();
        assertThat(zqb.exchange()).isNull();

        // Semicolon dialect, no 'active' column at all: ZQA is renamed, ZQB is byte-for-byte what
        // is stored and is reported as a duplicate rather than an update.
        CsvImportResult second = importAs(
                TestSecurityConfiguration.adminHeaders(),
                """
                symbol;name;exchange;currency;assetType;sector
                ZQA;ZQA Holdings plc;NASDAQ;USD;STOCK;Technology
                ZQB;ZQB Fund;;EUR;ETF;
                """);
        assertThat(second.processedRows()).isEqualTo(1);
        assertThat(second.duplicateRows()).isEqualTo(1);
        assertThat(get("ZQA").name()).isEqualTo("ZQA Holdings plc");
        assertThat(get("ZQA").active()).isTrue();
    }

    @Test
    void anOrdinaryUserCannotImport() {
        ResponseEntity<String> response = restTemplate.exchange(
                IMPORT,
                HttpMethod.POST,
                new HttpEntity<>(
                        multipart("symbol,name,currency,assetType\r\nZQX,Nope,USD,STOCK\r\n"),
                        multipartHeaders(TestSecurityConfiguration.userHeaders())),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void theExportImportsBackAsNothingButDuplicates() {
        importAs(
                TestSecurityConfiguration.adminHeaders(),
                "symbol,name,currency,assetType\nZQE,ZQE \"Quoted\", Inc.,USD,STOCK\n".replace(
                        "ZQE \"Quoted\", Inc.", "\"ZQE \"\"Quoted\"\", Inc.\""));

        ResponseEntity<String> export = restTemplate.exchange(
                "/api/v1/instruments/export",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.adminHeaders()),
                String.class);
        assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(export.getBody()).isNotNull().contains("symbol,name,exchange,currency,assetType,sector,active");
        assertThat(get("ZQE").name()).isEqualTo("ZQE \"Quoted\", Inc.");

        CsvImportResult again = importAs(TestSecurityConfiguration.adminHeaders(), export.getBody());
        assertThat(again.failedRows()).isZero();
        assertThat(again.processedRows()).isZero();
        assertThat(again.duplicateRows()).isEqualTo(again.totalRows());
    }

    @Test
    void anInstrumentWithDividendsIsRefusedWithAnExplanation() {
        create("ZQF");
        create("ZQG");
        ResponseEntity<String> dividend = restTemplate.exchange(
                "/api/v1/dividends",
                HttpMethod.POST,
                new HttpEntity<>(
                        new DividendRequest(get("ZQF").id(), new BigDecimal("1.5"), "USD", LocalDate.of(2026, 5, 1)),
                        TestSecurityConfiguration.userHeaders()),
                String.class);
        assertThat(dividend.getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<String> refused = delete("ZQF");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("Deactivate");

        assertThat(delete("ZQG").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private CsvImportResult importAs(HttpHeaders headers, String content) {
        ResponseEntity<CsvImportResult> response = restTemplate.exchange(
                IMPORT,
                HttpMethod.POST,
                new HttpEntity<>(multipart(content), multipartHeaders(headers)),
                CsvImportResult.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private InstrumentResponse get(String symbol) {
        ResponseEntity<InstrumentResponse> response = restTemplate.exchange(
                "/api/v1/instruments/" + symbol,
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                InstrumentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void create(String symbol) {
        ResponseEntity<InstrumentResponse> response = restTemplate.exchange(
                "/api/v1/instruments",
                HttpMethod.POST,
                new HttpEntity<>(
                        new InstrumentRequest(symbol, symbol + " Corp.", "NASDAQ", "USD", AssetType.STOCK, null, true),
                        TestSecurityConfiguration.adminHeaders()),
                InstrumentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<String> delete(String symbol) {
        return restTemplate.exchange(
                "/api/v1/instruments/" + symbol,
                HttpMethod.DELETE,
                new HttpEntity<>(TestSecurityConfiguration.adminHeaders()),
                String.class);
    }

    private static MultiValueMap<String, Object> multipart(String content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "instruments.csv";
            }
        });
        return body;
    }

    private static HttpHeaders multipartHeaders(HttpHeaders headers) {
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return headers;
    }
}
