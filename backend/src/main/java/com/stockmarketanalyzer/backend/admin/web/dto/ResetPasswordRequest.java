package com.stockmarketanalyzer.backend.admin.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A replacement password, submitted by an administrator on someone else's behalf.
 *
 * <p>There is no "current password" field, on purpose: this is not a self-service change, and an
 * administrator resetting an account is precisely the case where the old password is unknown.
 * The authority for the action is the caller's {@code ADMIN} role, checked before the controller.
 */
@Schema(description = "A new password for a user")
public record ResetPasswordRequest(
        @Schema(format = "password") @NotBlank @Size(min = 8, max = 255) String password,
        @Schema(description = "Force another change at next sign-in") boolean temporary) {

    @Override
    public String toString() {
        return "ResetPasswordRequest[password=***, temporary=%s]".formatted(temporary);
    }
}
