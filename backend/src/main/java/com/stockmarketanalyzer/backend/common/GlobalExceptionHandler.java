package com.stockmarketanalyzer.backend.common;

import com.stockmarketanalyzer.backend.admin.UserDirectoryUnavailableException;
import com.stockmarketanalyzer.backend.auth.AuthUnavailableException;
import com.stockmarketanalyzer.backend.auth.InvalidCredentialsException;
import com.stockmarketanalyzer.backend.inflation.feed.InflationFeedException;
import com.stockmarketanalyzer.backend.wealth.ratefeed.RateFeedException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Translates application exceptions into RFC 9457 problem details.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final URI NOT_FOUND_TYPE = URI.create("urn:stock-market-analyzer:not-found");
    private static final URI CONFLICT_TYPE = URI.create("urn:stock-market-analyzer:conflict");
    private static final URI VALIDATION_TYPE = URI.create("urn:stock-market-analyzer:validation-failed");
    private static final URI UPSTREAM_TYPE = URI.create("urn:stock-market-analyzer:upstream-unavailable");
    private static final URI UNAUTHORIZED_TYPE = URI.create("urn:stock-market-analyzer:unauthorized");

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", ex.getMessage(), NOT_FOUND_TYPE);
    }

    @ExceptionHandler(ResourceConflictException.class)
    public ProblemDetail handleConflict(ResourceConflictException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), CONFLICT_TYPE);
    }

    /**
     * A constraint the database enforces and the service did not check first: a duplicate
     * unique key, or a row still referenced by another table -- {@code dividend.instrument_id}
     * is deliberately {@code ON DELETE RESTRICT}, so deleting an instrument someone has been
     * paid by lands here rather than quietly destroying their history.
     *
     * <p>The cause is logged but never returned: it carries table, column and constraint names.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Database rejected the write", ex);
        return problem(
                HttpStatus.CONFLICT,
                "Conflict",
                "The database rejected this change: the row is referenced by other records, "
                        + "or it would duplicate one that already exists.",
                CONFLICT_TYPE);
    }

    /**
     * Somebody else's server let us down. A 502 rather than a 500 or a 400: nothing here is the
     * caller's fault and nothing is wrong with this application, so the honest answer is that the
     * upstream could not be used -- and pressing the button again later is a reasonable response.
     */
    @ExceptionHandler(RateFeedException.class)
    public ProblemDetail handleRateFeedFailure(RateFeedException ex) {
        log.warn("Rate feed unavailable", ex);
        return problem(HttpStatus.BAD_GATEWAY, "Rate feed unavailable", ex.getMessage(), UPSTREAM_TYPE);
    }

    /** The inflation source let us down -- a 502 for the same reasons as the rate feed's. */
    @ExceptionHandler(InflationFeedException.class)
    public ProblemDetail handleInflationFeedFailure(InflationFeedException ex) {
        log.warn("Inflation feed unavailable", ex);
        return problem(HttpStatus.BAD_GATEWAY, "Inflation source unavailable", ex.getMessage(), UPSTREAM_TYPE);
    }

    /**
     * A rejected sign-in or a spent refresh token. The message is fixed and says nothing about
     * which of the two it was, nor whether the username exists: a login endpoint that
     * distinguishes them is an account-enumeration oracle.
     *
     * <p>Deliberately carries no {@code WWW-Authenticate} header. This is a failed form
     * submission, not a challenge to retry with a bearer token, and the browser would answer a
     * challenge with a native credential dialog the SPA has no use for.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex) {
        log.debug("Rejected a sign-in attempt", ex);
        return problem(
                HttpStatus.UNAUTHORIZED,
                "Unauthorized",
                "The username or password is incorrect, or the session has expired.",
                UNAUTHORIZED_TYPE);
    }

    /**
     * Keycloak was unreachable or answered with something unusable. A 502 for the same reason
     * the rate feed gets one: nothing is wrong with the caller's request, and trying again in a
     * minute is a reasonable response.
     */
    @ExceptionHandler(AuthUnavailableException.class)
    public ProblemDetail handleAuthUnavailable(AuthUnavailableException ex) {
        log.warn("Identity provider unavailable", ex);
        return problem(
                HttpStatus.BAD_GATEWAY,
                "Sign-in unavailable",
                "The identity provider could not be reached. Try again in a moment.",
                UPSTREAM_TYPE);
    }

    /**
     * Keycloak's Admin API could not be used while an administrator was managing users. A 502
     * rather than a 500: nothing is wrong with the request, and the message says which of the
     * two likely causes it is -- unreachable, or a service account missing a realm-management
     * role, which is a deployment fault nobody at the keyboard can fix by trying again.
     *
     * <p>Told apart from {@link AuthUnavailableException} deliberately: "sign-in is unavailable"
     * would be both wrong and alarming when every other session is working perfectly.
     */
    @ExceptionHandler(UserDirectoryUnavailableException.class)
    public ProblemDetail handleUserDirectoryUnavailable(UserDirectoryUnavailableException ex) {
        log.warn("User directory unavailable", ex);
        return problem(HttpStatus.BAD_GATEWAY, "User administration unavailable", ex.getMessage(), UPSTREAM_TYPE);
    }

    /**
     * A {@code sort=} parameter naming a property the entity does not have. The tables only send
     * columns they mark sortable, so this is a hand-written URL or a client/server mismatch -- the
     * caller's mistake, and a 400 that names the property rather than a 500 with a stack trace.
     */
    @ExceptionHandler(PropertyReferenceException.class)
    public ProblemDetail handleUnknownSortProperty(PropertyReferenceException ex) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid request",
                "Cannot sort by '%s'.".formatted(ex.getPropertyName()),
                VALIDATION_TYPE);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage(), VALIDATION_TYPE);
    }

    /**
     * The multipart parser rejects an oversized upload before any controller sees it, so a CSV
     * import of the wrong file would otherwise surface as a 500 with a stack trace.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("Rejected an oversized upload", ex);
        return problem(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Upload too large",
                "That file is too large to upload. Split it and import it in parts.",
                VALIDATION_TYPE);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(MethodArgumentNotValidException ex) {
        ProblemDetail detail = problem(
                HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid.", VALIDATION_TYPE);
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        detail.setProperty("errors", errors);
        return detail;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", ex.getMessage(), VALIDATION_TYPE);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detailText, URI type) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, detailText);
        detail.setTitle(title);
        detail.setType(type);
        detail.setProperty("timestamp", Instant.now());
        return detail;
    }
}
