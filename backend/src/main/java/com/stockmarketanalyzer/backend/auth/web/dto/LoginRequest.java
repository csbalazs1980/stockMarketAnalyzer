package com.stockmarketanalyzer.backend.auth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** Credentials posted by the login form. They are forwarded to Keycloak and never stored. */
@Schema(description = "Credentials for the proxied sign-in")
public record LoginRequest(
        @Schema(example = "sma-user") @NotBlank String username,
        @Schema(format = "password") @NotBlank String password) {

    /** Keeps the password out of logs, stack traces and error payloads. */
    @Override
    public String toString() {
        return "LoginRequest[username=%s, password=***]".formatted(username);
    }
}
