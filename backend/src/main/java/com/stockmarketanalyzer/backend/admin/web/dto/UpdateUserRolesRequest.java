package com.stockmarketanalyzer.backend.admin.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The complete set of realm roles the user should end up with.
 *
 * <p>A whole set rather than add/remove deltas: the screen shows checkboxes, the administrator
 * answers "these", and a delta computed in the browser would be wrong the moment two people had
 * the dialog open. The service diffs it against what Keycloak currently holds.
 */
@Schema(description = "The realm roles a user should have after this call")
public record UpdateUserRolesRequest(
        @Schema(example = "[\"USER\",\"ADMIN\"]") @NotNull List<String> roles) {}
