package com.qflow.backend.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * Writes security errors in the same JSON shape as {@code ApiError}
 * ({@code status, error, message, path, timestamp}) without depending on a Jackson mapper.
 */
final class SecurityErrorResponses {

    private SecurityErrorResponses() {
    }

    static void write(HttpServletResponse response, int status, String error, String message, String path)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":" + status
                + ",\"error\":\"" + escape(error)
                + "\",\"message\":\"" + escape(message)
                + "\",\"path\":\"" + escape(path)
                + "\",\"timestamp\":\"" + LocalDateTime.now() + "\"}");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
