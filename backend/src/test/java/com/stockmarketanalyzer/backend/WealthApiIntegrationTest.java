package com.stockmarketanalyzer.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockmarketanalyzer.backend.common.PageResponse;
import com.stockmarketanalyzer.backend.wealth.web.dto.ExchangeRateRequest;
import com.stockmarketanalyzer.backend.wealth.web.dto.ExchangeRateResponse;
import com.stockmarketanalyzer.backend.wealth.web.dto.WealthEntryCloneOverride;
import com.stockmarketanalyzer.backend.wealth.web.dto.WealthEntryCloneRequest;
import com.stockmarketanalyzer.backend.wealth.web.dto.WealthEntryRequest;
import com.stockmarketanalyzer.backend.wealth.web.dto.WealthEntryResponse;
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
 * Exercises the wealth slice end to end: an ordinary USER (not an admin) records rates and
 * entries, the API values them on read, and another subject sees none of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WealthApiIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 8, 5);

    /** The clone tests work on their own, earlier pair of dates -- see the comment in the test. */
    private static final LocalDate CLONE_FROM = LocalDate.of(2026, 3, 2);

    private static final LocalDate CLONE_TO = LocalDate.of(2026, 3, 31);

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void recordsASnapshotAndValuesItFromTheOwnersRates() {
        // 1 USD = 314 HUF, 1 OZ of silver = 21550 HUF -- the owner's own marks, not a feed.
        storeRate(new ExchangeRateRequest("usd", "huf", new BigDecimal("314"), SNAPSHOT));
        storeRate(new ExchangeRateRequest("OZ", "HUF", new BigDecimal("21550"), SNAPSHOT));

        WealthEntryResponse savings = createEntry(
                new WealthEntryRequest("savings", "USD", new BigDecimal("36775"), SNAPSHOT, null));
        assertThat(savings.unit()).isEqualTo("USD");
        assertThat(savings.valueUnit()).isEqualTo("HUF");
        assertThat(savings.currentValue()).isEqualByComparingTo("11547350");

        WealthEntryResponse revolut = createEntry(
                new WealthEntryRequest("rev", "usd", new BigDecimal("3437"), SNAPSHOT, savings.id()));
        assertThat(revolut.parentId()).isEqualTo(savings.id());
        assertThat(revolut.unit()).isEqualTo("USD");

        createEntry(new WealthEntryRequest("investment silver", "oz", new BigDecimal("130"), SNAPSHOT, null));
        // Junk silver is measured in kg, and no kg rate was ever quoted.
        createEntry(new WealthEntryRequest("junk silver", "kg", new BigDecimal("2"), SNAPSHOT, null));

        ResponseEntity<WealthEntryResponse[]> latest = get("/api/v1/wealth/entries/latest");
        assertThat(latest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(latest.getBody()).hasSize(4);

        WealthEntryResponse silver = byName(latest.getBody(), "investment silver");
        assertThat(silver.currentValue()).isEqualByComparingTo("2801500");

        // No rate connects KG to HUF, so the value is absent rather than zero.
        assertThat(byName(latest.getBody(), "junk silver").currentValue()).isNull();

        // Asking for USD makes the accounts pass through untouched.
        ResponseEntity<WealthEntryResponse> inUsd = restTemplate.exchange(
                "/api/v1/wealth/entries/" + savings.id() + "?valueIn=USD",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                WealthEntryResponse.class);
        assertThat(inUsd.getBody()).isNotNull();
        assertThat(inUsd.getBody().valueUnit()).isEqualTo("USD");
        assertThat(inUsd.getBody().currentValue()).isEqualByComparingTo("36775");

        // The parent aggregates a child, so deleting it needs an explicit cascade.
        ResponseEntity<String> refused = restTemplate.exchange(
                "/api/v1/wealth/entries/" + savings.id(),
                HttpMethod.DELETE,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // A different Keycloak subject shares the database but sees none of these rows.
        ResponseEntity<WealthEntryResponse[]> otherUser = restTemplate.exchange(
                "/api/v1/wealth/entries/latest",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.adminHeaders()),
                WealthEntryResponse[].class);
        assertThat(otherUser.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otherUser.getBody()).isEmpty();

        ResponseEntity<String> foreignRow = restTemplate.exchange(
                "/api/v1/wealth/entries/" + savings.id(),
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.adminHeaders()),
                String.class);
        assertThat(foreignRow.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void clonesASubtreeIntoANewSnapshotWithoutTouchingTheOriginal() {
        // Dated before SNAPSHOT on purpose: the container is shared across the methods of this
        // class, and rows newer than SNAPSHOT would change what /latest returns for the test above.
        storeRate(new ExchangeRateRequest("USD", "HUF", new BigDecimal("300"), CLONE_FROM));
        storeRate(new ExchangeRateRequest("USD", "HUF", new BigDecimal("320"), CLONE_TO));

        // A pure aggregate: it holds nothing itself, it only sums the two accounts under it.
        WealthEntryResponse market = createEntry(new WealthEntryRequest("stock market", "USD", null, CLONE_FROM, null));
        WealthEntryResponse revolut =
                createEntry(new WealthEntryRequest("rev", "USD", new BigDecimal("3245"), CLONE_FROM, market.id()));
        WealthEntryResponse tbsz =
                createEntry(new WealthEntryRequest("tbsz24", "USD", new BigDecimal("1189"), CLONE_FROM, market.id()));

        WealthEntryCloneRequest request = new WealthEntryCloneRequest(
                CLONE_TO,
                List.of(
                        // The aggregate stays an aggregate: the null quantity is sent on purpose.
                        new WealthEntryCloneOverride(market.id(), null, "USD"),
                        new WealthEntryCloneOverride(revolut.id(), new BigDecimal("3978"), null),
                        new WealthEntryCloneOverride(tbsz.id(), new BigDecimal("1189"), "  ")));

        ResponseEntity<WealthEntryResponse[]> response = restTemplate.exchange(
                "/api/v1/wealth/entries/" + market.id() + "/clone",
                HttpMethod.POST,
                new HttpEntity<>(request, TestSecurityConfiguration.userHeaders()),
                WealthEntryResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).hasSize(3);

        WealthEntryResponse copiedRoot = response.getBody()[0];
        assertThat(copiedRoot.id()).isNotEqualTo(market.id());
        assertThat(copiedRoot.name()).isEqualTo("stock market");
        assertThat(copiedRoot.entryDate()).isEqualTo(CLONE_TO);
        assertThat(copiedRoot.quantity()).isNull();
        // The copy hangs where the source hung -- beside it, not under it.
        assertThat(copiedRoot.parentId()).isNull();

        WealthEntryResponse copiedRevolut = byName(response.getBody(), "rev");
        assertThat(copiedRevolut.parentId()).isEqualTo(copiedRoot.id());
        assertThat(copiedRevolut.quantity()).isEqualByComparingTo("3978");
        // A null unit inherits the row above rather than defaulting to anything.
        assertThat(copiedRevolut.unit()).isEqualTo("USD");
        // Valued from the rate quoted on the copy's own date, not the source's.
        assertThat(copiedRevolut.currentValue()).isEqualByComparingTo("1272960");

        // A blank unit is treated the same as a missing one.
        assertThat(byName(response.getBody(), "tbsz24").unit()).isEqualTo("USD");

        // The aggregate is worth its subtree, and the subtree is all one unit.
        assertThat(copiedRoot.totalValue()).isEqualByComparingTo("1653440");
        assertThat(copiedRoot.totalValuePartial()).isFalse();
        assertThat(copiedRoot.totalQuantity()).isEqualByComparingTo("5167");

        // The source snapshot is untouched: it still has its three rows and its own numbers.
        List<WealthEntryResponse> source = page("/api/v1/wealth/entries?from=" + CLONE_FROM + "&to=" + CLONE_FROM);
        assertThat(source).hasSize(3);
        assertThat(byName(source, "rev").quantity()).isEqualByComparingTo("3245");
    }

    @Test
    void refusesAnOverrideForARowOutsideTheClonedSubtree() {
        WealthEntryResponse cash =
                createEntry(new WealthEntryRequest("kp", "HUF", new BigDecimal("1550000"), CLONE_FROM, null));
        WealthEntryResponse unrelated =
                createEntry(new WealthEntryRequest("junk silver", "KG", new BigDecimal("2"), CLONE_FROM, null));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/wealth/entries/" + cash.id() + "/clone",
                HttpMethod.POST,
                new HttpEntity<>(
                        new WealthEntryCloneRequest(
                                CLONE_TO, List.of(new WealthEntryCloneOverride(unrelated.id(), BigDecimal.ONE, null))),
                        TestSecurityConfiguration.userHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("is not part of the subtree");

        // Nothing was written: the overrides are checked before the first insert.
        assertThat(page("/api/v1/wealth/entries?from=" + CLONE_TO + "&to=" + CLONE_TO + "&search=kp"))
                .isEmpty();
    }

    @Test
    void rejectsAnEntryWhoseParentIsNotTheCallers() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/wealth/entries",
                HttpMethod.POST,
                new HttpEntity<>(
                        new WealthEntryRequest("orphan", "HUF", new BigDecimal("1"), SNAPSHOT, 987_654L),
                        TestSecurityConfiguration.userHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("No wealth entry found");
    }

    @Test
    void rejectsAnUnauthenticatedWrite() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/wealth/entries",
                HttpMethod.POST,
                new HttpEntity<>(new WealthEntryRequest("kp", "HUF", new BigDecimal("1550000"), SNAPSHOT, null)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private WealthEntryResponse createEntry(WealthEntryRequest request) {
        ResponseEntity<WealthEntryResponse> response = restTemplate.exchange(
                "/api/v1/wealth/entries",
                HttpMethod.POST,
                new HttpEntity<>(request, TestSecurityConfiguration.userHeaders()),
                WealthEntryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private void storeRate(ExchangeRateRequest request) {
        ResponseEntity<ExchangeRateResponse> response = restTemplate.exchange(
                "/api/v1/wealth/exchange-rates",
                HttpMethod.POST,
                new HttpEntity<>(request, TestSecurityConfiguration.userHeaders()),
                ExchangeRateResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<WealthEntryResponse[]> get(String path) {
        return restTemplate.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                WealthEntryResponse[].class);
    }

    /** The paged list endpoint, unwrapped. {@code /latest} returns a bare array; this does not. */
    private List<WealthEntryResponse> page(String path) {
        ResponseEntity<PageResponse<WealthEntryResponse>> response = restTemplate.exchange(
                path + "&size=500",
                HttpMethod.GET,
                new HttpEntity<>(TestSecurityConfiguration.userHeaders()),
                new ParameterizedTypeReference<PageResponse<WealthEntryResponse>>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().content();
    }

    private static WealthEntryResponse byName(WealthEntryResponse[] entries, String name) {
        return byName(java.util.Arrays.asList(entries), name);
    }

    private static WealthEntryResponse byName(List<WealthEntryResponse> entries, String name) {
        return entries.stream()
                .filter(entry -> entry.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No entry named " + name));
    }
}
