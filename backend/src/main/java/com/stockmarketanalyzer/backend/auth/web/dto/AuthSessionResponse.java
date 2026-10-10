package com.stockmarketanalyzer.backend.auth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Who the caller is, as far as the frontend needs to know.
 *
 * <p>Conspicuously absent: the tokens. They are in {@code HttpOnly} cookies the browser attaches
 * by itself, and the SPA is not given the means to read them. {@code expiresInSeconds} is the
 * one thing it does need -- not to use the token, but to know when to ask for a new one.
 */
@Schema(description = "The signed-in user, derived server-side from the access token")
public record AuthSessionResponse(
        @Schema(example = "sma-user") String username,
        @Schema(example = "Sample User") String displayName,
        @Schema(example = "[\"USER\"]") List<String> roles,
        @Schema(description = "Seconds until the access token expires", example = "1800") long expiresInSeconds) {}
