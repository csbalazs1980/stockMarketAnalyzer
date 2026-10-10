package com.stockmarketanalyzer.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockmarketanalyzer.backend.common.PageResponse;
import com.stockmarketanalyzer.backend.common.csv.CsvImportResult;
import com.stockmarketanalyzer.backend.dividend.web.dto.DividendResponse;
import com.stockmarketanalyzer.backend.instrument.domain.AssetType;
import com.stockmarketanalyzer.backend.instrument.web.dto.InstrumentRequest;
import com.stockmarketanalyzer.backend.instrument.web.dto.InstrumentResponse;
import com.stockmarketanalyzer.backend.wealth.web.dto.ExchangeRateResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
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
 * The CSV upload endpoints, end to end.
 *
 * <p>What is worth testing here is not that a well-formed file imports -- it is everything
 * around that: a bad row being skipped rather than failing the file, an upload of the same file
 * twice not doubling anything, the two spreadsheet dialects both being understood, and the
 * "that is not a CSV" refusals landing as 400s with nothing written.
 *
 * <p>The Postgres container is static and shared with the other integration tests, so this class
 * works on units and tickers nothing else uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CsvImportApiIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String RATES = "/api/v1/wealth/exchange-rates/import";
    private static final String DIVIDENDS = "/api/v1/dividends/import";
    private static final String EXPORT_RATES = "/api/v1/wealth/exchange-rates/export";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void importsTheGoodRatesAndSkipsTheRestWithoutFailingTheFile() {
        CsvImportResult result = importOk(
                RATES,
                "rates.csv",
                """
                sourceUnit,destinationUnit,rate,rateDate
                XAA,XBB,10.5,2026-03-01
                XAA,XBB,not-a-number,2026-03-02
                XAA,XAA,3,2026-03-03
                XAA,XBB,11,2026-03-04
                """);

        // Two good rows; the unparseable rate and the pair that converts a unit into itself are
        // counted and skipped rather than taking the other two down with them.
        assertThat(result.totalRows()).isEqualTo(4);
        assertThat(result.processedRows()).isEqualTo(2);
        assertThat(result.failedRows()).isEqualTo(2);
        assertThat(result.duplicateRows()).isZero();

        ResponseEntity<ExchangeRateResponse> inForce = restTemplate.exchange(
                "/api/v1/wealth/exchange-rates/in-force?sourceUnit=XAA&destinationUnit=XBB&on=2026-03-03",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                ExchangeRateResponse.class);
        assertThat(inForce.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(inForce.getBody()).isNotNull();
        assertThat(inForce.getBody().rate()).isEqualByComparingTo("10.5");

        // Re-uploading a corrected file overwrites the quotes rather than adding to them: each
        // row is an upsert on (pair, date), which is why rates have no duplicate count.
        CsvImportResult again = importOk(
                RATES,
                "rates.csv",
                """
                sourceUnit;destinationUnit;rate;rateDate
                XAA;XBB;12,25;2026.03.01.
                """);
        assertThat(again.processedRows()).isEqualTo(1);

        ResponseEntity<PageResponse<ExchangeRateResponse>> stored = restTemplate.exchange(
                "/api/v1/wealth/exchange-rates?sourceUnit=XAA&destinationUnit=XBB",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                new ParameterizedTypeReference<PageResponse<ExchangeRateResponse>>() {});
        assertThat(stored.getBody()).isNotNull();
        // Still two rows, not three -- and the semicolon file's comma decimal was read correctly.
        assertThat(stored.getBody().content()).hasSize(2);
        assertThat(stored.getBody().content().stream()
                        .filter(rate -> rate.rateDate().getDayOfMonth() == 1)
                        .findFirst()
                        .orElseThrow()
                        .rate())
                .isEqualByComparingTo("12.25");
    }

    @Test
    void importsDividendsBySymbolAndSkipsRowsItAlreadyHolds() {
        InstrumentResponse instrument = createInstrument("IMPA", "Import Test Co.");

        CsvImportResult result = importOk(
                DIVIDENDS,
                "dividends.csv",
                """
                symbol,amount,unit,paymentDate
                IMPA,42.75,usd,2026-04-15
                NOSUCH,10,USD,2026-04-16
                IMPA,7.5,USD,2026-04-17
                """);

        assertThat(result.totalRows()).isEqualTo(3);
        assertThat(result.processedRows()).isEqualTo(2);
        // An unknown ticker is a row that fails, never an instrument the import invents.
        assertThat(result.failedRows()).isEqualTo(1);

        List<DividendResponse> stored = page("/api/v1/dividends?instrumentId=" + instrument.id());
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).unit()).isEqualTo("USD");

        // The same file again: a dividend has no natural key, so without this check re-uploading
        // an overlapping month would quietly double the history.
        CsvImportResult second = importOk(
                DIVIDENDS,
                "dividends.csv",
                """
                symbol,amount,unit,paymentDate
                IMPA,42.75,USD,2026-04-15
                IMPA,7.50,USD,2026-04-17
                IMPA,1.25,USD,2026-04-18
                """);
        assertThat(second.processedRows()).isEqualTo(1);
        // 42.75 and 7.50 are already there -- and 7.50 matches the stored 7.5 despite the scale.
        assertThat(second.duplicateRows()).isEqualTo(2);
        assertThat(page("/api/v1/dividends?instrumentId=" + instrument.id())).hasSize(3);
    }

    @Test
    void refusesAnythingThatIsNotACsvFileWithoutWritingAnything() {
        String rates = "sourceUnit,destinationUnit,rate,rateDate\nXCC,XDD,1,2026-01-01\n";
        assertThat(importFailure(RATES, "rates.xlsx", rates)).contains("not a CSV file");

        // A spreadsheet renamed to .csv still gives itself away by its first two bytes.
        MultiValueMap<String, Object> zip = multipart("rates.csv", new byte[] {'P', 'K', 3, 4, 0, 1, 2, 3});
        ResponseEntity<String> renamed = restTemplate.exchange(
                RATES, HttpMethod.POST, new HttpEntity<>(zip, multipartHeaders()), String.class);
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(renamed.getBody()).contains("spreadsheet");

        assertThat(importFailure(RATES, "rates.csv", "sourceUnit,destinationUnit,rate\nXCC,XDD,1\n"))
                .contains("rateDate");

        assertThat(importFailure(RATES, "rates.csv", "sourceUnit,destinationUnit,rate,rateDate\n"))
                .contains("no data rows");

        // Nothing above reached the database.
        ResponseEntity<PageResponse<ExchangeRateResponse>> stored = restTemplate.exchange(
                "/api/v1/wealth/exchange-rates?sourceUnit=XCC",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                new ParameterizedTypeReference<PageResponse<ExchangeRateResponse>>() {});
        assertThat(stored.getBody()).isNotNull();
        assertThat(stored.getBody().content()).isEmpty();
    }

    @Test
    void servesASampleWhoseHeaderIsTheOneTheImportWants() {
        for (String path : List.of(
                "/api/v1/wealth/exchange-rates/import/sample", "/api/v1/dividends/import/sample")) {
            ResponseEntity<String> sample = restTemplate.exchange(
                    path, HttpMethod.GET, new HttpEntity<>(TestSecurityConfiguration.userHeaders()), String.class);

            assertThat(sample.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(sample.getHeaders().getContentType()).isNotNull();
            assertThat(sample.getHeaders().getContentType().toString()).startsWith("text/csv");
            assertThat(sample.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                    .contains("attachment")
                    .contains(".csv");
            assertThat(sample.getBody()).isNotNull();
            // Header plus exactly one example row, so the file it produces is the file it wants.
            assertThat(sample.getBody().strip().lines()).hasSize(2);
        }
    }

    @Test
    void exportsTheCallersOwnRowsInAFileTheImportAcceptsBack() {
        importOk(
                RATES,
                "rates.csv",
                """
                sourceUnit,destinationUnit,rate,rateDate
                XGG,XHH,13.5,2026-05-01
                XGG,XHH,14,2026-05-02
                """);

        ResponseEntity<String> export = restTemplate.exchange(
                EXPORT_RATES,
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                String.class);

        assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(export.getHeaders().getContentType()).isNotNull();
        assertThat(export.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(export.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("exchange-rates-");
        assertThat(export.getBody()).isNotNull();
        // The byte-order mark is for Excel; the header is the one the import asks for.
        assertThat(export.getBody()).startsWith("\uFEFFsourceUnit,destinationUnit,rate,rateDate");
        // Stored as NUMERIC(19,6) and written back without the trailing zeros that came with it.
        assertThat(export.getBody()).contains("XGG,XHH,13.5,2026-05-01").contains("XGG,XHH,14,2026-05-02");

        // The point of the whole thing: what comes out goes back in. Rates are upserts, so every
        // row is processed and none is a duplicate or a failure.
        CsvImportResult reimported = importOk(RATES, "export.csv", export.getBody());
        assertThat(reimported.processedRows()).isEqualTo(reimported.totalRows());
        assertThat(reimported.failedRows()).isZero();
    }

    @Test
    void exportsNobodyElsesRows() {
        importOk(
                RATES,
                "rates.csv",
                """
                sourceUnit,destinationUnit,rate,rateDate
                XII,XJJ,7.5,2026-06-01
                """);

        // Another subject entirely -- an administrator is not an exception to this.
        ResponseEntity<String> theirs = restTemplate.exchange(
                EXPORT_RATES,
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.adminHeaders()),
                String.class);

        assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(theirs.getBody()).isNotNull().doesNotContain("XII");
    }

    @Test
    void rejectsAnUnauthenticatedExport() {
        ResponseEntity<String> response =
                restTemplate.exchange(EXPORT_RATES, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsAnUnauthenticatedUpload() {
        MultiValueMap<String, Object> body =
                multipart("rates.csv", "sourceUnit,destinationUnit,rate,rateDate\nXEE,XFF,1,2026-01-01\n"
                        .getBytes(StandardCharsets.UTF_8));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response =
                restTemplate.exchange(RATES, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private CsvImportResult importOk(String path, String fileName, String content) {
        ResponseEntity<CsvImportResult> response = restTemplate.exchange(
                path,
                HttpMethod.POST,
                new HttpEntity<>(multipart(fileName, content.getBytes(StandardCharsets.UTF_8)), multipartHeaders()),
                CsvImportResult.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    /** Uploads a file that should be refused outright, and returns the problem detail body. */
    private String importFailure(String path, String fileName, String content) {
        ResponseEntity<String> response = restTemplate.exchange(
                path,
                HttpMethod.POST,
                new HttpEntity<>(multipart(fileName, content.getBytes(StandardCharsets.UTF_8)), multipartHeaders()),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    /**
     * The filename matters -- it is the first thing the importer checks -- and a plain
     * ByteArrayResource does not have one, so it is supplied here.
     */
    private static MultiValueMap<String, Object> multipart(String fileName, byte[] content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        return body;
    }

    private static HttpHeaders multipartHeaders() {
        HttpHeaders headers = TestSecurityConfiguration.userHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return headers;
    }

    private InstrumentResponse createInstrument(String symbol, String name) {
        ResponseEntity<InstrumentResponse> response = restTemplate.exchange(
                "/api/v1/instruments",
                HttpMethod.POST,
                new HttpEntity<>(
                        new InstrumentRequest(symbol, name, "NASDAQ", "USD", AssetType.STOCK, null, true),
                        TestSecurityConfiguration.adminHeaders()),
                InstrumentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private List<DividendResponse> page(String path) {
        ResponseEntity<PageResponse<DividendResponse>> response = restTemplate.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                new ParameterizedTypeReference<PageResponse<DividendResponse>>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().content();
    }
}
