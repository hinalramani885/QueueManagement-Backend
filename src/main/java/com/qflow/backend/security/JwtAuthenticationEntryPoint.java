package com.qflow.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Returns a JSON 401. When the request carried an expired/invalid JWT the message says so
 * (e.g. "JWT token is expired"), which the frontend uses to log the user out.
 */
@Slf4j
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object jwtError = request.getAttribute(JwtAuthenticationFilter.JWT_ERROR_ATTRIBUTE);
        String message = jwtError != null ? jwtError.toString() : "Full authentication is required to access this resource";
        log.debug("Unauthorized request to {}: {}", request.getRequestURI(), message);
        SecurityErrorResponses.write(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized", message, request.getRequestURI());
    }
}
