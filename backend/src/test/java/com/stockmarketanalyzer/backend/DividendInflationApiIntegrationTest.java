package com.stockmarketanalyzer.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockmarketanalyzer.backend.common.PageResponse;
import com.stockmarketanalyzer.backend.dividend.web.dto.DividendRequest;
import com.stockmarketanalyzer.backend.dividend.web.dto.DividendResponse;
import com.stockmarketanalyzer.backend.dividend.web.dto.DividendSummaryResponse;
import com.stockmarketanalyzer.backend.inflation.web.dto.InflationRateRequest;
import com.stockmarketanalyzer.backend.instrument.domain.AssetType;
import com.stockmarketanalyzer.backend.instrument.web.dto.InstrumentRequest;
import com.stockmarketanalyzer.backend.instrument.web.dto.InstrumentResponse;
import com.stockmarketanalyzer.backend.wealth.web.dto.ExchangeRateRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The received-dividends table's "Inflation adjusted" option.
 *
 * <p>Made-up currencies (XDA paid, XDB valued in, XDC with no inflation data) so the shared
 * container's HUF/EUR/USD figures cannot change the expected numbers. The first payment is the
 * user's own example in disguise: 20 paid in 2023 at a rate of 300, i.e. 6000 in the value
 * currency; the amount is adjusted from 20 with the paid currency's inflation, the value from 6000
 * with the value currency's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DividendInflationApiIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String RANGE = "from=2023-01-01&to=2025-12-31";

    @Autowired
    private TestRestTemplate restTemplate;

    /** Static: JUnit makes a new instance per test, and the container outlives them all. */
    private static Long instrumentId;

    /** Seeded once by whichever test runs first; JUnit's method order is not source order. */
    private void seed() {
        if (instrumentId != null) {
            return;
        }
        instrumentId = createInstrument("ZIFA").id();

        inflation("XDA", 2023, "3.0");
        inflation("XDA", 2024, "2.0");
        inflation("XDB", 2023, "17.0");
        inflation("XDB", 2024, "3.7");
        inflation("XDB", 2025, "4.4");

        rate("XDA", "300", LocalDate.of(2023, 1, 1));
        rate("XDC", "100", LocalDate.of(2023, 1, 1));

        dividend("20", "XDA", LocalDate.of(2023, 5, 10)); // 6000 XDB
        dividend("10", "XDA", LocalDate.of(2025, 3, 1)); // 3000 XDB; XDA has no 2025 figure yet
        dividend("50", "XDC", LocalDate.of(2024, 7, 1)); // 5000 XDB; XDC has no inflation data
    }

    @Test
    void adjustsTheAmountWithItsOwnCurrencyAndTheValueWithTheValueCurrency() {
        seed();
        List<DividendResponse> rows = list("&sort=paymentDate,asc&inflationAdjusted=true");

        DividendResponse first = rows.get(0);
        // The stored figures are untouched -- an edit dialog fed from this row saves 20, not 21.012.
        assertThat(first.amount()).isEqualByComparingTo("20");
        assertThat(first.currentValue()).isEqualByComparingTo("6000");
        // 20 x 1.03 x 1.02
        assertThat(first.inflationAdjusted().amount()).isEqualByComparingTo("21.012");
        // 6000 x 1.17 x 1.037 x 1.044, converted first and adjusted second
        assertThat(first.inflationAdjusted().currentValue()).isEqualByComparingTo("7600.048560");
        assertThat(first.inflationAdjusted().complete()).isTrue();

        // Paid in 2025, after XDA's latest figure: nothing to apply to the amount; the value
        // currency has 2025, so the value gets that one year.
        DividendResponse third = rows.get(2);
        assertThat(third.paymentDate()).isEqualTo(LocalDate.of(2025, 3, 1));
        assertThat(third.inflationAdjusted().amount()).isEqualByComparingTo("10");
        assertThat(third.inflationAdjusted().currentValue()).isEqualByComparingTo("3132");
        assertThat(third.inflationAdjusted().complete()).isTrue();

        // No data for XDC: the amount is left as it is and the row says so; the value is still
        // adjusted with XDB, 2024 and 2025: 5000 x 1.037 x 1.044.
        DividendResponse second = rows.get(1);
        assertThat(second.inflationAdjusted().amount()).isEqualByComparingTo("50");
        assertThat(second.inflationAdjusted().currentValue()).isEqualByComparingTo("5413.14");
        assertThat(second.inflationAdjusted().complete()).isFalse();

        // Without the option nothing extra is sent.
        assertThat(list("&sort=paymentDate,asc").get(0).inflationAdjusted()).isNull();
    }

    @Test
    void sortsByTheAdjustedFiguresAcrossPages() {
        seed();
        // Sorted in memory over every matching row, so page 2 continues where page 1 stopped.
        List<DividendResponse> page0 = list("&sort=value,desc&inflationAdjusted=true&size=2&page=0");
        List<DividendResponse> page1 = list("&sort=value,desc&inflationAdjusted=true&size=2&page=1");
        assertThat(page0).extracting(row -> row.inflationAdjusted().currentValue().intValue())
                .containsExactly(7600, 5413);
        assertThat(page1).extracting(row -> row.inflationAdjusted().currentValue().intValue())
                .containsExactly(3132);

        List<DividendResponse> byAmount = list("&sort=amount,desc&inflationAdjusted=true");
        assertThat(byAmount).extracting(row -> row.inflationAdjusted().amount().intValue())
                .containsExactly(50, 21, 10);
    }

    @Test
    void theSummaryTotalsTheAdjustedValuesAndCountsWhatCouldNotBeAdjusted() {
        seed();
        DividendSummaryResponse plain = summary("");
        assertThat(plain.total()).isEqualByComparingTo("14000");
        assertThat(plain.inflationAdjusted()).isFalse();
        assertThat(plain.notInflationAdjusted()).isZero();

        DividendSummaryResponse adjusted = summary("&inflationAdjusted=true");
        // 7600.04856 + 5413.14 + 3132
        assertThat(adjusted.total()).isEqualByComparingTo("16145.18856");
        assertThat(adjusted.inflationAdjusted()).isTrue();
        assertThat(adjusted.notInflationAdjusted()).isEqualTo(1);
    }

    private List<DividendResponse> list(String extra) {
        ResponseEntity<PageResponse<DividendResponse>> response = restTemplate.exchange(
                "/api/v1/dividends?" + RANGE + "&instrumentId=" + instrumentId + "&valueIn=XDB" + extra,
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                new ParameterizedTypeReference<PageResponse<DividendResponse>>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().content();
    }

    private DividendSummaryResponse summary(String extra) {
        ResponseEntity<DividendSummaryResponse> response = restTemplate.exchange(
                "/api/v1/dividends/summary?" + RANGE + "&instrumentId=" + instrumentId + "&valueIn=XDB" + extra,
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                DividendSummaryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private InstrumentResponse createInstrument(String symbol) {
        ResponseEntity<InstrumentResponse> response = restTemplate.exchange(
                "/api/v1/instruments",
                HttpMethod.POST,
                new HttpEntity<>(
                        new InstrumentRequest(symbol, symbol + " Corp.", "NYSE", "USD", AssetType.STOCK, null, true),
                        TestSecurityConfiguration.adminHeaders()),
                InstrumentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private void inflation(String unit, int year, String rate) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/inflation-rates",
                HttpMethod.POST,
                new HttpEntity<>(
                        new InflationRateRequest(unit, year, new BigDecimal(rate)),
                        TestSecurityConfiguration.adminHeaders()),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void rate(String source, String rate, LocalDate date) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/wealth/exchange-rates",
                HttpMethod.POST,
                new HttpEntity<>(
                        new ExchangeRateRequest(source, "XDB", new BigDecimal(rate), date),
                        TestSecurityConfiguration.userHeaders()),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private void dividend(String amount, String unit, LocalDate date) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/dividends",
                HttpMethod.POST,
                new HttpEntity<>(
                        new DividendRequest(instrumentId, new BigDecimal(amount), unit, date),
                        TestSecurityConfiguration.userHeaders()),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
