package com.stockmarketanalyzer.backend.admin.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * A user of the realm, as an administrator needs to see them.
 *
 * <p>Conspicuously absent: anything credential-shaped. Keycloak does not hand back password
 * hashes and this application does not ask -- a password can be replaced here, never read.
 *
 * <p>{@code id} is the Keycloak subject. It is the same value wealth entries and dividends are
 * owned by, which is why deleting users is not offered: the rows would be orphaned and there is
 * nothing to re-attach them to.
 */
@Schema(description = "A user of the realm")
public record AdminUserResponse(
        @Schema(description = "Keycloak subject (sub)", example = "5f0a1c2e-9b1d-4a5e-8c3f-6d7e8f901234") String id,
        @Schema(example = "sma-user") String username,
        @Schema(example = "user@stockmarket.local") String email,
        @Schema(example = "Read") String firstName,
        @Schema(example = "Only") String lastName,
        @Schema(description = "A disabled user keeps their data but cannot sign in") boolean enabled,
        @Schema(description = "Realm roles mapped directly to this user", example = "[\"USER\"]") List<String> roles,
        @Schema(description = "When the account was created") Instant createdAt) {}
