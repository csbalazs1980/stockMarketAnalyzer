package com.stockmarketanalyzer.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** Returns a problem detail instead of an empty 403 when the token lacks the required role. */
@Component
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {

        ProblemDetailResponseWriter.write(
                request,
                response,
                HttpStatus.FORBIDDEN,
                "Forbidden",
                "The authenticated user does not hold the role required for this operation.");
    }
}
