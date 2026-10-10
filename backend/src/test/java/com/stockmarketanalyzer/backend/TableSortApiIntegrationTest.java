package com.stockmarketanalyzer.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockmarketanalyzer.backend.common.PageResponse;
import com.stockmarketanalyzer.backend.dividend.web.dto.DividendRequest;
import com.stockmarketanalyzer.backend.dividend.web.dto.DividendResponse;
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
 * The column sorting the tables send as {@code sort=field,dir}: a joined property (the dividend's
 * ticker) has to work, and a property that does not exist has to be a 400, not a 500.
 *
 * <p>Works in January 2019 with Z-prefixed tickers, which nothing else in the shared container uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TableSortApiIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String RANGE = "from=2019-01-01&to=2019-01-31";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void dividendsSortByTheJoinedTickerAndByAmount() {
        long zsb = instrument("ZSB");
        long zsa = instrument("ZSA");
        dividend(zsb, "5", LocalDate.of(2019, 1, 10));
        dividend(zsa, "9", LocalDate.of(2019, 1, 11));
        dividend(zsb, "1", LocalDate.of(2019, 1, 12));

        assertThat(page("instrument.symbol,asc").stream().map(DividendResponse::instrumentSymbol))
                .containsExactly("ZSA", "ZSB", "ZSB");
        assertThat(page("amount,desc").stream().map(dividend -> dividend.amount().intValue()))
                .containsExactly(9, 5, 1);
    }

    /**
     * The converted value is not a column, so the service sorts it in memory over every matching
     * row. Three dividends whose order by amount is the reverse of their order by value, plus one
     * with no rate, which must come last whichever way round.
     */
    @Test
    void dividendsSortByTheConvertedValueAcrossPages() {
        long zsv = instrument("ZSV");
        storeRate("ZVA", "1");
        storeRate("ZVB", "100");
        dividend(zsv, "50", "ZVA", LocalDate.of(2019, 2, 10)); // worth 50
        dividend(zsv, "2", "ZVB", LocalDate.of(2019, 2, 11)); // worth 200
        dividend(zsv, "9", "ZVC", LocalDate.of(2019, 2, 12)); // no rate
        dividend(zsv, "30", "ZVA", LocalDate.of(2019, 2, 13)); // worth 30

        String feb = "from=2019-02-01&to=2019-02-28&valueIn=ZVH";
        assertThat(amounts(feb + "&sort=value,desc")).containsExactly(2, 50, 30, 9);
        assertThat(amounts(feb + "&sort=value,asc")).containsExactly(30, 50, 2, 9);

        // Paged: the second page carries on from the first rather than re-sorting its own rows.
        assertThat(amounts(feb + "&sort=value,desc&size=2&page=0")).containsExactly(2, 50);
        assertThat(amounts(feb + "&sort=value,desc&size=2&page=1")).containsExactly(30, 9);
    }

    @Test
    void anUnknownSortPropertyIsABadRequest() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/dividends?sort=nonsense,asc",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("nonsense");
    }

    private List<Integer> amounts(String query) {
        ResponseEntity<PageResponse<DividendResponse>> response = restTemplate.exchange(
                "/api/v1/dividends?" + query,
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                new ParameterizedTypeReference<PageResponse<DividendResponse>>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().totalElements()).isEqualTo(4);
        return response.getBody().content().stream().map(dividend -> dividend.amount().intValue()).toList();
    }

    /** A personal rate into the test's own value unit, ZVH, quoted before every payment. */
    private void storeRate(String source, String rate) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/wealth/exchange-rates",
                HttpMethod.POST,
                new HttpEntity<>(
                        new ExchangeRateRequest(source, "ZVH", new BigDecimal(rate), LocalDate.of(2019, 1, 1)),
                        TestSecurityConfiguration.userHeaders()),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private List<DividendResponse> page(String sort) {
        ResponseEntity<PageResponse<DividendResponse>> response = restTemplate.exchange(
                "/api/v1/dividends?" + RANGE + "&sort=" + sort,
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                new ParameterizedTypeReference<PageResponse<DividendResponse>>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().content();
    }

    private long instrument(String symbol) {
        ResponseEntity<InstrumentResponse> response = restTemplate.exchange(
                "/api/v1/instruments",
                HttpMethod.POST,
                new HttpEntity<>(
                        new InstrumentRequest(symbol, symbol + " Corp.", "NASDAQ", "USD", AssetType.STOCK, null, true),
                        TestSecurityConfiguration.adminHeaders()),
                InstrumentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private void dividend(long instrumentId, String amount, LocalDate paid) {
        dividend(instrumentId, amount, "USD", paid);
    }

    private void dividend(long instrumentId, String amount, String unit, LocalDate paid) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/dividends",
                HttpMethod.POST,
                new HttpEntity<>(
                        new DividendRequest(instrumentId, new BigDecimal(amount), unit, paid),
                        TestSecurityConfiguration.userHeaders()),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
