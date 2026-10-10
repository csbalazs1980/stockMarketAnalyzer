package com.stockmarketanalyzer.backend.auth;

/**
 * Keycloak could not be reached, or answered with something that is not a credential failure.
 *
 * <p>Kept apart from {@link InvalidCredentialsException} because the two need opposite answers:
 * a 401 tells the user to check their password, which is unhelpful and wrong when the identity
 * provider is simply down.
 */
public class AuthUnavailableException extends RuntimeException {

    public AuthUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
