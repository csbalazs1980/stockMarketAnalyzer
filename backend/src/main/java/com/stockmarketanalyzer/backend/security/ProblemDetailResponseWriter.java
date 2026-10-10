package com.stockmarketanalyzer.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Writes an RFC 9457 problem detail directly to the servlet response.
 *
 * <p>Security failures happen in the filter chain, before {@code @RestControllerAdvice} gets a
 * chance to run, so {@code GlobalExceptionHandler} never sees them. Without this the client
 * gets an empty 401/403 body — inconsistent with every other error the API returns.
 *
 * <p>The JSON is assembled by hand rather than through an {@code ObjectMapper}: the payload is
 * a fixed five-field shape, and this keeps the class independent of which Jackson version the
 * application happens to be using.
 */
final class ProblemDetailResponseWriter {

    private ProblemDetailResponseWriter() {}

    static void write(
            HttpServletRequest request, HttpServletResponse response, HttpStatus status, String title, String detail)
            throws IOException {

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        String body =
                """
                {"type":"urn:stock-market-analyzer:%s","title":"%s","status":%d,"detail":"%s","instance":"%s","timestamp":"%s"}"""
                        .formatted(
                                status == HttpStatus.UNAUTHORIZED ? "unauthorized" : "forbidden",
                                escape(title),
                                status.value(),
                                escape(detail),
                                escape(request.getRequestURI()),
                                Instant.now());

        response.getWriter().write(body);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append("\\u%04x".formatted((int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
