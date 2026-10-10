package com.stockmarketanalyzer.backend.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Demands a CSRF token exactly when the request is authenticated by a cookie the browser
 * attached on its own.
 *
 * <p>CSRF is a consequence of ambient credentials, not of the HTTP method. A client that sends
 * {@code Authorization: Bearer ...} has to be in possession of the token and cannot be made to
 * send it by a third-party page, so requiring a CSRF token of it protects nothing and would
 * break Swagger UI, curl and the integration tests. A browser holding an {@code HttpOnly}
 * session cookie is the opposite case: the cookie rides along on any request the browser can be
 * tricked into making, so a write needs a second factor the attacker's page cannot read.
 *
 * <p>Safe methods are excluded here rather than relied on elsewhere: setting
 * {@code requireCsrfProtectionMatcher} replaces Spring Security's default matcher wholesale,
 * including its method filter.
 */
public class CookieCredentialCsrfMatcher implements RequestMatcher {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final Set<String> credentialCookieNames;

    public CookieCredentialCsrfMatcher(String... credentialCookieNames) {
        this.credentialCookieNames = Set.of(credentialCookieNames);
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod())) {
            return false;
        }
        if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            return false;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (credentialCookieNames.contains(cookie.getName())
                    && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return true;
            }
        }
        return false;
    }
}
