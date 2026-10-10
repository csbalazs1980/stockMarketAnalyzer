package com.stockmarketanalyzer.backend.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Reads and writes the two {@code HttpOnly} cookies the tokens live in.
 *
 * <p>Every token this application issues to a browser goes through here, so the security
 * attributes are decided in exactly one place. {@code HttpOnly} is not negotiable: a token a
 * script can read is a token an injected script can steal, and that is the difference between
 * this design and keeping tokens in {@code localStorage}.
 *
 * <p>The refresh cookie is scoped to {@code /api/v1/auth}. Nothing outside those four endpoints
 * has any use for it, so it is simply not attached to the hundreds of ordinary API calls that
 * make up a session -- a smaller window for it to be logged, cached or mislaid in.
 *
 * <p>The clearing cookies repeat path, {@code sameSite} and {@code secure} exactly. A browser
 * matches a deletion against name, domain and path only, but an attribute mismatch on
 * {@code secure} or {@code sameSite} makes some browsers refuse to store the deletion at all,
 * which shows up as a sign-out that visibly does nothing.
 */
@Component
@RequiredArgsConstructor
public class AuthCookies {

    private final AuthProperties properties;

    public void write(HttpServletResponse response, TokenSet tokens) {
        AuthProperties.Cookie config = properties.getCookie();
        add(response, build(config.getAccessName(), tokens.accessToken(), config.getPath(), tokens.accessExpiry()));
        add(
                response,
                build(
                        config.getRefreshName(),
                        tokens.refreshToken(),
                        config.getRefreshPath(),
                        tokens.refreshExpiry()));
    }

    public void clear(HttpServletResponse response) {
        AuthProperties.Cookie config = properties.getCookie();
        add(response, build(config.getAccessName(), "", config.getPath(), Duration.ZERO));
        add(response, build(config.getRefreshName(), "", config.getRefreshPath(), Duration.ZERO));
    }

    /**
     * The refresh token, if the browser sent one. There is deliberately no reader for the access
     * cookie: {@code CookieBearerTokenResolver} hands that one to the resource server, and a
     * second way to reach it would be a second thing to keep in step.
     */
    public Optional<String> refreshToken(HttpServletRequest request) {
        return read(request, properties.getCookie().getRefreshName());
    }

    private ResponseCookie build(String name, String value, String path, Duration maxAge) {
        AuthProperties.Cookie config = properties.getCookie();
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(config.isSecure())
                .path(path)
                .sameSite(config.getSameSite())
                // A session cookie (no Max-Age) would survive the token inside it. Matching the
                // lifetimes means an expired token is simply not sent, which the SPA reads as
                // "signed out" without a round trip.
                .maxAge(maxAge)
                .build();
    }

    private static void add(HttpServletResponse response, ResponseCookie cookie) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static Optional<String> read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }
}
