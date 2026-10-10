package com.stockmarketanalyzer.backend.admin.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A privilege that can be granted, read from the realm rather than hard-coded.
 *
 * <p>Keycloak's own built-in realm roles ({@code default-roles-*}, {@code offline_access},
 * {@code uma_authorization}) are filtered out upstream: they are protocol machinery, not
 * application privileges, and offering them as choices would invite somebody to revoke one.
 */
@Schema(description = "An assignable realm role")
public record RealmRoleResponse(
        @Schema(example = "ADMIN") String name,
        @Schema(example = "Full access, including every write endpoint.") String description) {}
