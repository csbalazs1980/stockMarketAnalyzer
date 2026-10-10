package com.stockmarketanalyzer.backend.admin.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A new account, as an administrator describes it.
 *
 * <p>The password is set here rather than emailed: this application has no mail transport, and a
 * flow that pretends to send an invitation nobody receives is worse than one that hands the
 * administrator a password to pass on. {@link #temporaryPassword()} is the mitigation -- Keycloak
 * then forces a change on first sign-in, so the password the administrator knows stops working
 * the moment the user has one of their own.
 *
 * <p>The length floor is a first line of defence only. Keycloak's realm password policy is the
 * real one, and its rejection surfaces as a 400 carrying Keycloak's own explanation.
 */
@Schema(description = "A user to create in the realm")
public record CreateUserRequest(
        @Schema(example = "jane.doe")
                @NotBlank
                @Size(max = 255)
                @Pattern(
                        regexp = "^[A-Za-z0-9._@+-]+$",
                        message = "may contain only letters, digits and . _ @ + -")
                String username,
        @Schema(example = "jane@example.com") @Email @Size(max = 255) String email,
        @Schema(example = "Jane") @Size(max = 255) String firstName,
        @Schema(example = "Doe") @Size(max = 255) String lastName,
        @Schema(format = "password", description = "Initial password") @NotBlank @Size(min = 8, max = 255)
                String password,
        @Schema(description = "Force a password change at first sign-in") boolean temporaryPassword,
        @Schema(description = "Realm roles to grant", example = "[\"USER\"]") List<String> roles) {

    /** Keeps the password out of logs, stack traces and error payloads. */
    @Override
    public String toString() {
        return "CreateUserRequest[username=%s, password=***]".formatted(username);
    }

    public List<String> rolesOrEmpty() {
        return roles == null ? List.of() : roles;
    }
}
