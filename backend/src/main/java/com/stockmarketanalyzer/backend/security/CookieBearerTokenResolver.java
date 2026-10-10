package com.stockmarketanalyzer.backend.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/**
 * Lets the resource server find the access token in an {@code HttpOnly} cookie.
 *
 * <p>This one class is what keeps the rest of the application unchanged. Everything downstream --
 * the JWT decoder, the realm-role converter, every {@code hasRole} rule, {@code CurrentUser} --
 * still sees an ordinary bearer token; only the place it was picked up from is different.
 *
 * <p>The {@code Authorization} header is still tried first, and wins. Swagger UI's <em>Authorize</em>
 * button, curl and the integration tests all send one, and none of them should be forced through
 * the cookie flow just because a browser somewhere uses it.
 */
public class CookieBearerTokenResolver implements BearerTokenResolver {

    private final BearerTokenResolver headerResolver = new DefaultBearerTokenResolver();
    private final String cookieName;

    public CookieBearerTokenResolver(String cookieName) {
        this.cookieName = cookieName;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        String fromHeader = headerResolver.resolve(request);
        if (fromHeader != null) {
            return fromHeader;
        }

        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : value;
            }
        }
        return null;
    }
}
