package com.stockmarketanalyzer.backend.auth.web;

import com.stockmarketanalyzer.backend.auth.AuthService;
import com.stockmarketanalyzer.backend.auth.web.dto.AuthSessionResponse;
import com.stockmarketanalyzer.backend.auth.web.dto.LoginRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The application's own login, standing in front of Keycloak.
 *
 * <p>These four endpoints are the entire authentication surface the browser knows about. There
 * is no redirect to an identity provider, no OIDC callback and no client secret in the page --
 * the user signs in to <em>this</em> application, and where the credentials actually go is an
 * implementation detail of the server.
 *
 * <p>Nothing here returns a token. The tokens are set as {@code HttpOnly} cookies, which the
 * browser attaches to subsequent calls on its own and no script can read.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Sign in, refresh and sign out")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    @Operation(
            summary = "Sign in",
            description = "Exchanges a username and password for a session. The access and refresh tokens "
                    + "are returned as HttpOnly cookies, never in the body.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Signed in"),
        @ApiResponse(responseCode = "401", description = "The credentials were rejected"),
        @ApiResponse(responseCode = "502", description = "The identity provider could not be reached")
    })
    public AuthSessionResponse login(
            @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        return authService.login(request, response);
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "Refresh the session",
            description = "Spends the refresh cookie for a new pair of cookies. Both tokens are rotated.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Refreshed"),
        @ApiResponse(responseCode = "401", description = "No usable refresh token; the cookies are cleared")
    })
    public AuthSessionResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        return authService.refresh(request, response);
    }

    @PostMapping("/logout")
    @Operation(
            summary = "Sign out",
            description = "Clears the cookies and revokes the Keycloak session behind the refresh token. "
                    + "Succeeds even when there was no session to end.")
    @ApiResponse(responseCode = "204", description = "Signed out")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(request, response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/session")
    @Operation(
            summary = "Who is signed in",
            description = "Restores the session on a page load. Returns 204 for an anonymous visitor rather "
                    + "than 401, because not being signed in is an ordinary state and not an error. "
                    + "If the access cookie has expired but the refresh cookie is still good, the session "
                    + "is renewed here, so a reload costs one request rather than two.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Signed in"),
        @ApiResponse(responseCode = "204", description = "Nobody is signed in")
    })
    public ResponseEntity<AuthSessionResponse> session(HttpServletRequest request, HttpServletResponse response) {
        return authService
                .session(request, response)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
