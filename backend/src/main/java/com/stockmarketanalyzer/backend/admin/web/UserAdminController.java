package com.stockmarketanalyzer.backend.admin.web;

import com.stockmarketanalyzer.backend.admin.UserAdminService;
import com.stockmarketanalyzer.backend.admin.web.dto.AdminUserResponse;
import com.stockmarketanalyzer.backend.admin.web.dto.CreateUserRequest;
import com.stockmarketanalyzer.backend.admin.web.dto.RealmRoleResponse;
import com.stockmarketanalyzer.backend.admin.web.dto.ResetPasswordRequest;
import com.stockmarketanalyzer.backend.admin.web.dto.UpdateUserEnabledRequest;
import com.stockmarketanalyzer.backend.admin.web.dto.UpdateUserRolesRequest;
import com.stockmarketanalyzer.backend.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The administrator's view of the realm's users, proxied through this application.
 *
 * <p>Keycloak stays invisible here for the same reason it is invisible at sign-in: the browser
 * talks only to this application, and the credential that reaches Keycloak -- the service
 * account's -- never leaves the server. An administrator therefore needs no Keycloak console
 * account and no {@code realm-management} roles of their own.
 *
 * <p>{@code ADMIN} is required twice over, on purpose. {@code SecurityConfig} matches
 * {@code /api/v1/admin/**} before its method-based rules, which is what stops a {@code GET} here
 * from falling through to "any valid token"; the {@link PreAuthorize} below is what keeps the
 * guarantee attached to the code if that rule is ever reordered.
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(
        name = "User administration",
        description = "Create users, reset passwords and assign privileges. Administrators only. "
                + "Backed by Keycloak's Admin API, which the browser never talks to directly.")
public class UserAdminController {

    private static final String PROBLEM_JSON = "application/problem+json";

    private final UserAdminService service;

    @GetMapping("/users")
    @Operation(
            summary = "List users",
            description = "Paged. The optional search matches username, first name, last name and email, "
                    + "as Keycloak's own user search does.")
    @ApiResponse(responseCode = "200", description = "Users returned (possibly empty)")
    public PageResponse<AdminUserResponse> list(
            @Parameter(description = "Free-text search across username, name and email.", example = "jane")
                    @RequestParam(required = false)
                    String search,
            @Parameter(description = "Zero-based page index.") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(
                            description =
                                    "Page size, up to 1000 like every other list. Each row still costs one "
                                            + "role-mapping lookup at Keycloak, so a very large page of a "
                                            + "very large realm is slow -- in practice the realm is small.")
                    @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(1000)
                    int size) {
        return service.list(search, page, size);
    }

    @GetMapping("/users/{id}")
    @Operation(summary = "Get one user")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "User found"),
        @ApiResponse(
                responseCode = "404",
                description = "No such user in the realm",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public AdminUserResponse get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Create a user",
            description = "The account is created enabled, with the given password already set, and with the "
                    + "requested realm roles granted. Mark the password temporary to make Keycloak force a "
                    + "change at first sign-in.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "User created"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed, an unknown role was requested, or the realm's password "
                        + "policy rejected the password",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "That username or email address is already taken",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public AdminUserResponse create(@RequestBody @Valid CreateUserRequest request) {
        return service.create(request);
    }

    @PutMapping("/users/{id}/password")
    @Operation(
            summary = "Set a user's password",
            description = "Replaces the password outright. The old one is not required -- an administrator "
                    + "resetting an account is precisely the case where nobody knows it.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Password replaced"),
        @ApiResponse(
                responseCode = "400",
                description = "The realm's password policy rejected the password",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "No such user in the realm",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<Void> resetPassword(
            @PathVariable String id, @RequestBody @Valid ResetPasswordRequest request) {
        service.resetPassword(id, request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{id}/roles")
    @Operation(
            summary = "Set a user's privileges",
            description = "The roles sent are the roles the user ends up with; anything omitted is revoked. "
                    + "You cannot revoke ADMIN from yourself.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Roles applied"),
        @ApiResponse(
                responseCode = "400",
                description = "An unknown role was requested, or the caller tried to demote themselves",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "No such user in the realm",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public AdminUserResponse setRoles(
            @PathVariable String id, @RequestBody @Valid UpdateUserRolesRequest request) {
        return service.setRoles(id, request.roles());
    }

    @PutMapping("/users/{id}/enabled")
    @Operation(
            summary = "Enable or disable a user",
            description = "A disabled account cannot sign in but keeps everything recorded under it. This is "
                    + "the way to revoke access: deleting the user would orphan their wealth and dividend "
                    + "rows, which are owned by the Keycloak subject. You cannot disable yourself.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Applied"),
        @ApiResponse(
                responseCode = "400",
                description = "The caller tried to disable their own account",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "No such user in the realm",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public AdminUserResponse setEnabled(
            @PathVariable String id, @RequestBody @Valid UpdateUserEnabledRequest request) {
        return service.setEnabled(id, request.enabled());
    }

    @GetMapping("/roles")
    @Operation(
            summary = "List assignable privileges",
            description = "The realm's own roles, read from Keycloak rather than hard-coded here, so a role "
                    + "added to the realm becomes assignable without a code change. Keycloak's internal "
                    + "roles (default-roles-*, offline_access, uma_authorization) are left out.")
    @ApiResponse(responseCode = "200", description = "Roles returned")
    public List<RealmRoleResponse> roles() {
        return service.assignableRoles();
    }
}
