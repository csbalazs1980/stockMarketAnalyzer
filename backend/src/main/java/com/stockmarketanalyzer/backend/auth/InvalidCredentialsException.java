package com.stockmarketanalyzer.backend.auth;

/**
 * The credentials, or the refresh token, were rejected by Keycloak.
 *
 * <p>Keycloak answers {@code invalid_grant} for a wrong password, an unknown user, a disabled
 * account and an expired refresh token alike, and this class deliberately preserves that
 * flattening: telling a caller <em>which</em> of those it was is an account-enumeration oracle.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
