package com.stockmarketanalyzer.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockmarketanalyzer.backend.common.csv.CsvImportResult;
import com.stockmarketanalyzer.backend.inflation.web.dto.InflationRateRequest;
import com.stockmarketanalyzer.backend.inflation.web.dto.InflationRateResponse;
import java.math.BigDecimal;
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
 * The inflation administration screen's backend: edit by id, and the CSV round trip.
 *
 * <p>Works on made-up Z-prefixed currencies so nothing collides with the other inflation tests
 * in the shared container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InflationRateCsvApiIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String BASE = "/api/v1/inflation-rates";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createsNewYearsUpdatesKnownOnesAndCountsTheRest() {
        CsvImportResult first = importAs(
                TestSecurityConfiguration.adminHeaders(),
                """
                unit,year,rate
                zia,2020,3.3
                ZIA,2021,-0.25
                ZIA,2022,14.5%
                ZIA,,2.0
                ZIA,2023,lots
                ZIA,1800,2.0
                ZIA,2024,-120
                """);
        // Lower-case currency is normalised, deflation and a trailing % are fine; a missing year,
        // a non-number, a year out of range and a fall of more than 100% each fail one row.
        assertThat(first.totalRows()).isEqualTo(7);
        assertThat(first.processedRows()).isEqualTo(3);
        assertThat(first.failedRows()).isEqualTo(4);
        assertThat(first.duplicateRows()).isZero();
        assertThat(get("ZIA", 2021).rate()).isEqualByComparingTo("-0.25");
        assertThat(get("ZIA", 2022).rate()).isEqualByComparingTo("14.5");

        // Semicolon dialect with comma decimals: 2020 is revised, 2021 is unchanged (written with
        // trailing zeros, which must not count as a change), 2025 is new.
        CsvImportResult second = importAs(
                TestSecurityConfiguration.adminHeaders(),
                """
                unit;year;rate
                ZIA;2020;3,4
                ZIA;2021;-0,2500
                ZIA;2025;4,6
                """);
        assertThat(second.processedRows()).isEqualTo(2);
        assertThat(second.duplicateRows()).isEqualTo(1);
        assertThat(second.failedRows()).isZero();
        assertThat(get("ZIA", 2020).rate()).isEqualByComparingTo("3.4");

        ResponseEntity<List<String>> units = restTemplate.exchange(
                BASE + "/units",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                new ParameterizedTypeReference<List<String>>() {});
        assertThat(units.getBody()).contains("ZIA");
    }

    @Test
    void anOrdinaryUserCannotImport() {
        ResponseEntity<String> response = restTemplate.exchange(
                BASE + "/import",
                HttpMethod.POST,
                new HttpEntity<>(
                        multipart("unit,year,rate\r\nZIX,2025,1.0\r\n"),
                        multipartHeaders(TestSecurityConfiguration.userHeaders())),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aFileWithoutTheRateColumnIsRefusedWhole() {
        ResponseEntity<String> response = restTemplate.exchange(
                BASE + "/import",
                HttpMethod.POST,
                new HttpEntity<>(
                        multipart("unit,year\nZIY,2025\n"), multipartHeaders(TestSecurityConfiguration.adminHeaders())),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void theExportImportsBackAsNothingButDuplicates() {
        importAs(TestSecurityConfiguration.adminHeaders(), "unit,year,rate\nZIB,2019,3.3\nZIB,2020,100\n");

        ResponseEntity<String> export = restTemplate.exchange(
                BASE + "/export",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.adminHeaders()),
                String.class);
        assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 100.0000 must come out as 100, not 1E+2, or the reader would refuse its own export.
        assertThat(export.getBody()).isNotNull().contains("unit,year,rate").contains("ZIB,2020,100\r\n");

        CsvImportResult again = importAs(TestSecurityConfiguration.adminHeaders(), export.getBody());
        assertThat(again.failedRows()).isZero();
        assertThat(again.processedRows()).isZero();
        assertThat(again.duplicateRows()).isEqualTo(again.totalRows());
    }

    @Test
    void theSampleImportsCleanly() {
        ResponseEntity<String> sample = restTemplate.exchange(
                BASE + "/import/sample",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.adminHeaders()),
                String.class);
        assertThat(sample.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sample.getBody()).startsWith("unit,year,rate");
    }

    @Test
    void anEditCanMoveARowToAnotherYearButNotOntoAnOccupiedOne() {
        InflationRateResponse a = post(new InflationRateRequest("ZIC", 2030, new BigDecimal("2.0")));
        post(new InflationRateRequest("ZIC", 2031, new BigDecimal("3.0")));

        ResponseEntity<InflationRateResponse> moved = put(a.id(), new InflationRateRequest("zic", 2029, new BigDecimal("2.5")));
        assertThat(moved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(moved.getBody()).isNotNull();
        assertThat(moved.getBody().id()).isEqualTo(a.id());
        assertThat(moved.getBody().year()).isEqualTo(2029);
        assertThat(moved.getBody().rate()).isEqualByComparingTo("2.5");

        ResponseEntity<InflationRateResponse> clash = put(a.id(), new InflationRateRequest("ZIC", 2031, BigDecimal.ONE));
        assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(get("ZIC", 2031).rate()).isEqualByComparingTo("3.0");

        assertThat(put(Long.MAX_VALUE, new InflationRateRequest("ZIC", 2040, BigDecimal.ONE)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> asUser = restTemplate.exchange(
                BASE + "/" + a.id(),
                HttpMethod.PUT,
                new HttpEntity<>(
                        new InflationRateRequest("ZIC", 2029, BigDecimal.TEN), TestSecurityConfiguration.userHeaders()),
                String.class);
        assertThat(asUser.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private CsvImportResult importAs(HttpHeaders headers, String content) {
        ResponseEntity<CsvImportResult> response = restTemplate.exchange(
                BASE + "/import",
                HttpMethod.POST,
                new HttpEntity<>(multipart(content), multipartHeaders(headers)),
                CsvImportResult.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private InflationRateResponse get(String unit, int year) {
        ResponseEntity<InflationRateResponse> response = restTemplate.exchange(
                BASE + "/" + unit + "/" + year,
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                InflationRateResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private InflationRateResponse post(InflationRateRequest request) {
        ResponseEntity<InflationRateResponse> response = restTemplate.exchange(
                BASE,
                HttpMethod.POST,
                new HttpEntity<>(request, TestSecurityConfiguration.adminHeaders()),
                InflationRateResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ResponseEntity<InflationRateResponse> put(Long id, InflationRateRequest request) {
        return restTemplate.exchange(
                BASE + "/" + id,
                HttpMethod.PUT,
                new HttpEntity<>(request, TestSecurityConfiguration.adminHeaders()),
                InflationRateResponse.class);
    }

    private static MultiValueMap<String, Object> multipart(String content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "inflation-rates.csv";
            }
        });
        return body;
    }

    private static HttpHeaders multipartHeaders(HttpHeaders headers) {
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return headers;
    }
}
