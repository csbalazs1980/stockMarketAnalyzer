package com.stockmarketanalyzer.backend.admin;

/**
 * Keycloak's Admin API could not be used: unreachable, refusing the service account, or
 * answering with something this application cannot make sense of.
 *
 * <p>Separate from {@code AuthUnavailableException} because the two are told apart by the person
 * reading the message. "Sign-in is unavailable" is wrong and alarming when what actually failed
 * was an administrator listing users while every other session carries on working.
 */
public class UserDirectoryUnavailableException extends RuntimeException {

    public UserDirectoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
