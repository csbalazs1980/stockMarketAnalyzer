package com.stockmarketanalyzer.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/** Returns a problem detail (plus the required WWW-Authenticate header) instead of an empty 401. */
@Component
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {

        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"stockmarket\"");
        ProblemDetailResponseWriter.write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "Unauthorized",
                "A valid bearer token issued by Keycloak is required.");
    }
}
