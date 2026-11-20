package com.queueless.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

/**
 * Authenticates requests carrying a "Bearer" JWT.
 * <p>
 * An invalid or expired token does not abort the request: the request simply continues
 * unauthenticated, so public endpoints keep working for users with a stale token, while
 * protected endpoints are rejected by {@link JwtAuthenticationEntryPoint}, which reports
 * the reason stored in {@link #JWT_ERROR_ATTRIBUTE}.
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String JWT_ERROR_ATTRIBUTE = "jwt.error";

    private final JwtTokenProvider jwtProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                if (jwtProvider.validateToken(token)) {
                    String userId = jwtProvider.getUserIdFromToken(token);
                    String email = jwtProvider.getEmailFromToken(token);
                    Collection<GrantedAuthority> authorities = jwtProvider.getAuthoritiesFromToken(token);

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(userId, null, authorities);
                    authentication.setDetails(Map.of(
                            "userId", userId,
                            "email", email == null ? "" : email
                    ));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    log.debug("Authenticated user: {} ({})", email, userId);
                } else {
                    request.setAttribute(JWT_ERROR_ATTRIBUTE, "Invalid JWT token");
                }
            } catch (Exception e) {
                log.debug("JWT rejected for {}: {}", request.getRequestURI(), e.getMessage());
                SecurityContextHolder.clearContext();
                request.setAttribute(JWT_ERROR_ATTRIBUTE, e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }
}
