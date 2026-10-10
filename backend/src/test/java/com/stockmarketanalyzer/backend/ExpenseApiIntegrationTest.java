package com.stockmarketanalyzer.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockmarketanalyzer.backend.common.PageResponse;
import com.stockmarketanalyzer.backend.expense.domain.RecurrencePeriod;
import com.stockmarketanalyzer.backend.expense.web.dto.ExpenseCategoryRequest;
import com.stockmarketanalyzer.backend.expense.web.dto.ExpenseCategoryResponse;
import com.stockmarketanalyzer.backend.expense.web.dto.ExpenseItemRequest;
import com.stockmarketanalyzer.backend.expense.web.dto.ExpenseItemResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Exercises the expense slice end to end: recurring items owned by one subject, and the two kinds
 * of category -- personal, which anybody may create, and global, which only an administrator may.
 *
 * <p>The Postgres container is static and shared across the methods of this class, so each test
 * uses its own names rather than an unfiltered view of the table.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExpenseApiIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void recordsRecurringExpensesWithPersonalAndGlobalTags() {
        // A global tag, created by an administrator, is offered to everybody.
        ExpenseCategoryResponse shared = createCategory(new ExpenseCategoryRequest("Insurance", true), adminHeaders());
        assertThat(shared.global()).isTrue();

        ExpenseCategoryResponse own = createCategory(new ExpenseCategoryRequest("Car", false), userHeaders());
        assertThat(own.global()).isFalse();

        ExpenseItemResponse loan = createItem(new ExpenseItemRequest(
                "Car insurance",
                new BigDecimal("120000"),
                "huf",
                1,
                RecurrencePeriod.YEARLY,
                LocalDate.of(2025, 3, 15),
                LocalDate.of(2031, 3, 15),
                Set.of(shared.id(), own.id())));

        // The unit is normalised on the way in, like every other unit in this application.
        assertThat(loan.unit()).isEqualTo("HUF");
        // Tags come back alphabetically, and a global one sits among them as an equal.
        assertThat(loan.categories()).extracting(ExpenseCategoryResponse::category).containsExactly("Car", "Insurance");
        assertThat(loan.active()).isTrue();
        // Nothing normalised is returned: the interval and the period are what a client computes
        // a monthly figure from, and the API deliberately does not pick an average month for it.
        assertThat(loan.recurrenceInterval()).isEqualTo(1);
        assertThat(loan.recurrencePeriod()).isEqualTo(RecurrencePeriod.YEARLY);

        // The category filter finds it through the global tag as readily as through the own one.
        assertThat(items("/api/v1/expenses/items?categoryId=" + shared.id()))
                .extracting(ExpenseItemResponse::name)
                .contains("Car insurance");

        // A different Keycloak subject shares the database but sees none of these items.
        ResponseEntity<String> foreign = restTemplate.exchange(
                "/api/v1/expenses/items/" + loan.id(),
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                String.class);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void refusesGlobalCategoriesToAnOrdinaryUser() {
        ResponseEntity<String> refused = restTemplate.exchange(
                "/api/v1/expenses/categories",
                HttpMethod.POST,
                new HttpEntity<>(new ExpenseCategoryRequest("Rezsi", true), userHeaders()),
                String.class);

        // 403, not a silently personal category: asking for something you may not have should not
        // quietly give you something else.
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void treatsCategoryNamesCaseInsensitivelyAndWontShadowAGlobalOne() {
        createCategory(new ExpenseCategoryRequest("Travel", false), userHeaders());

        ResponseEntity<String> sameName = restTemplate.exchange(
                "/api/v1/expenses/categories",
                HttpMethod.POST,
                new HttpEntity<>(new ExpenseCategoryRequest("travel", false), userHeaders()),
                String.class);
        assertThat(sameName.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // A global tag blocks a personal copy of itself too: two identical labels in one picker,
        // one shared and one not, is a bug report waiting to happen.
        createCategory(new ExpenseCategoryRequest("Utilities", true), adminHeaders());
        ResponseEntity<String> shadow = restTemplate.exchange(
                "/api/v1/expenses/categories",
                HttpMethod.POST,
                new HttpEntity<>(new ExpenseCategoryRequest("UTILITIES", false), userHeaders()),
                String.class);
        assertThat(shadow.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void untagsItemsOnlyWhenAForcedDeleteAsksFor() {
        ExpenseCategoryResponse tag = createCategory(new ExpenseCategoryRequest("Streaming", false), userHeaders());
        ExpenseItemResponse netflix = createItem(new ExpenseItemRequest(
                "Netflix", new BigDecimal("4490"), "HUF", 1, RecurrencePeriod.MONTHLY, null, null, Set.of(tag.id())));
        assertThat(netflix.categories()).hasSize(1);

        ResponseEntity<String> refused = restTemplate.exchange(
                "/api/v1/expenses/categories/" + tag.id(),
                HttpMethod.DELETE,
                new HttpEntity<>(userHeaders()),
                String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<String> forced = restTemplate.exchange(
                "/api/v1/expenses/categories/" + tag.id() + "?force=true",
                HttpMethod.DELETE,
                new HttpEntity<>(userHeaders()),
                String.class);
        assertThat(forced.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The expense survives the tag. Losing a label must never lose what it was on.
        ResponseEntity<ExpenseItemResponse> survivor = restTemplate.exchange(
                "/api/v1/expenses/items/" + netflix.id(),
                HttpMethod.GET,
                new HttpEntity<>(userHeaders()),
                ExpenseItemResponse.class);
        assertThat(survivor.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(survivor.getBody()).isNotNull();
        assertThat(survivor.getBody().categories()).isEmpty();
    }

    @Test
    void refusesACategoryThatIsNotYours() {
        ExpenseCategoryResponse adminsOwn =
                createCategory(new ExpenseCategoryRequest("Admin only", false), adminHeaders());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/expenses/items",
                HttpMethod.POST,
                new HttpEntity<>(
                        new ExpenseItemRequest(
                                "Borrowed tag",
                                new BigDecimal("1"),
                                "HUF",
                                1,
                                RecurrencePeriod.MONTHLY,
                                null,
                                null,
                                Set.of(adminsOwn.id())),
                        userHeaders()),
                String.class);

        // 404, the same answer an id that does not exist gets: otherwise the status code becomes a
        // way of discovering what other people have tagged their expenses with.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void refusesAnExpenseThatExpiresBeforeItStarts() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/expenses/items",
                HttpMethod.POST,
                new HttpEntity<>(
                        new ExpenseItemRequest(
                                "Backwards",
                                new BigDecimal("10"),
                                "HUF",
                                1,
                                RecurrencePeriod.MONTHLY,
                                LocalDate.of(2026, 5, 1),
                                LocalDate.of(2026, 4, 1),
                                null),
                        userHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void hidesFinishedExpensesOnlyWhenAskedTo() {
        ExpenseItemResponse finished = createItem(new ExpenseItemRequest(
                "Old phone plan",
                new BigDecimal("3000"),
                "HUF",
                1,
                RecurrencePeriod.MONTHLY,
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2021, 1, 1),
                null));
        assertThat(finished.active()).isFalse();

        assertThat(items("/api/v1/expenses/items?search=phone"))
                .extracting(ExpenseItemResponse::name)
                .contains("Old phone plan");
        // activeOnly defaults to false, so nothing is hidden unless the caller asks.
        assertThat(items("/api/v1/expenses/items?search=phone&activeOnly=true")).isEmpty();
    }

    @Test
    void letsAnAdministratorRenameAGlobalCategoryAndNobodyElse() {
        ExpenseCategoryResponse global = createCategory(new ExpenseCategoryRequest("Rent draft", true), adminHeaders());

        ResponseEntity<String> refused = restTemplate.exchange(
                "/api/v1/expenses/categories/" + global.id(),
                HttpMethod.PUT,
                new HttpEntity<>(new ExpenseCategoryRequest("Rent", true), userHeaders()),
                String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<ExpenseCategoryResponse> renamed = restTemplate.exchange(
                "/api/v1/expenses/categories/" + global.id(),
                HttpMethod.PUT,
                new HttpEntity<>(new ExpenseCategoryRequest("Rent", true), adminHeaders()),
                ExpenseCategoryResponse.class);
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody()).isNotNull();
        assertThat(renamed.getBody().category()).isEqualTo("Rent");

        // Moving a row between the two kinds is not an edit: one reading would hand a personal
        // label to everybody, the other would strip a shared one from everybody.
        ResponseEntity<String> moved = restTemplate.exchange(
                "/api/v1/expenses/categories/" + global.id(),
                HttpMethod.PUT,
                new HttpEntity<>(new ExpenseCategoryRequest("Rent", false), adminHeaders()),
                String.class);
        assertThat(moved.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsAnUnauthenticatedWrite() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/expenses/items",
                HttpMethod.POST,
                new HttpEntity<>(new ExpenseItemRequest(
                        "Anonymous", new BigDecimal("1"), "HUF", 1, RecurrencePeriod.MONTHLY, null, null, null)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ExpenseCategoryResponse createCategory(ExpenseCategoryRequest request, HttpHeaders headers) {
        ResponseEntity<ExpenseCategoryResponse> response = restTemplate.exchange(
                "/api/v1/expenses/categories",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                ExpenseCategoryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private ExpenseItemResponse createItem(ExpenseItemRequest request) {
        ResponseEntity<ExpenseItemResponse> response = restTemplate.exchange(
                "/api/v1/expenses/items",
                HttpMethod.POST,
                new HttpEntity<>(request, userHeaders()),
                ExpenseItemResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private List<ExpenseItemResponse> items(String path) {
        ResponseEntity<PageResponse<ExpenseItemResponse>> response = restTemplate.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(userHeaders()),
                new ParameterizedTypeReference<PageResponse<ExpenseItemResponse>>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().content();
    }

    private static HttpHeaders userHeaders() {
        return TestSecurityConfiguration.userHeaders();
    }

    private static HttpHeaders adminHeaders() {
        return TestSecurityConfiguration.adminHeaders();
    }
}
