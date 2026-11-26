package com.qflow.backend.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtProvider;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;
    private final RateLimitFilter rateLimitFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Value("${cors.allowed-origins:http://localhost:5173,https://localhost:5173}")
    private List<String> allowedOrigins;

    /**
     * RateLimitFilter is a @Component so Spring Boot would also register it as a plain servlet filter.
     * It must only run inside the security filter chain.
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(allowedOrigins.stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Requested-With", "Accept",
                "Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers"));
        configuration.setExposedHeaders(Arrays.asList("Authorization"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler)
                )
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .preload(true)
                                .maxAgeInSeconds(31536000)
                        )
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'self'; " +
                                        "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdn.jsdelivr.net; " +
                                        "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://fonts.googleapis.com; " +
                                        "img-src 'self' data: https:; " +
                                        "font-src 'self' https://fonts.gstatic.com https://fonts.googleapis.com;")
                        )
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                        )
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
                        .xssProtection(xss -> xss
                                .headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK)
                        )
                )
                .addFilterBefore(rateLimitFilter, SecurityContextHolderFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Error dispatches must be reachable, otherwise every 4xx/5xx is rewritten to 401
                        .requestMatchers("/error").permitAll()
                        // Swagger UI
                        .requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/webjars/**"
                        ).permitAll()
                        // Public endpoints
                        .requestMatchers(
                                "/api/auth/**",
                                "/api/password/**",
                                "/api/password-reset-token/**",
                                "/ws/**",
                                "/api/payment/create-order",
                                "/api/payment/confirm",
                                "/api/payment/confirm-provider",
                                "/api/payment/failed",
                                "/api/search/**",
                                "/api/public/**",
                                "/actuator/health",
                                "/actuator/prometheus"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/queues/*/add-token-with-details"
                        ).hasRole("USER")
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/queues/*/token/*/user-details"
                        ).hasAnyRole("USER", "PROVIDER", "ADMIN")
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/queues/*/reset-with-options"
                        ).hasAnyRole("PROVIDER", "ADMIN")
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/user/favorites",
                                "/api/user/favorites/details"
                        ).authenticated()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/user/favorites/**"
                        ).authenticated()
                        .requestMatchers(
                                HttpMethod.DELETE,
                                "/api/user/favorites/**"
                        ).authenticated()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/feedback/**"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/feedback"
                        ).hasRole("USER")
                        .requestMatchers(HttpMethod.POST, "/api/payment/confirm-provider-bulk").hasRole("ADMIN")
                        .requestMatchers("/api/export/**").hasAnyRole("PROVIDER", "ADMIN")
                        // Public read-only endpoints
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/places",
                                "/api/places/top-rated",
                                "/api/places/{id}",
                                "/api/places/type/**",
                                "/api/places/nearby",
                                "/api/places/paginated",  // new paginated endpoint
                                "/api/services",
                                "/api/services/{id}",
                                "/api/services/place/**",
                                "/api/queues/all",
                                "/api/queues/by-place/**",
                                "/api/queues/by-service/**",
                                "/api/queues/{queueId}"
                        ).permitAll()
                        .requestMatchers("/uploads/**").permitAll()
                        // Information pages: everyone can read, only admins can change or delete
                        .requestMatchers(HttpMethod.GET, "/api/content/**").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/api/content/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/content/**").hasRole("ADMIN")
                        // User endpoints
                        .requestMatchers("/api/queues/*/add-token").hasRole("USER")
                        .requestMatchers(HttpMethod.GET, "/api/user/tokens").authenticated()
                        .requestMatchers("/api/user/**").hasAnyRole("USER", "ADMIN", "PROVIDER")
                        // Provider endpoints
                        .requestMatchers("/api/providers/**").hasRole("PROVIDER")
                        .requestMatchers("/api/queues/create").hasRole("PROVIDER")
                        .requestMatchers("/api/queues/by-provider").hasRole("PROVIDER")
                        .requestMatchers("/api/queues/*/serve-next").hasRole("PROVIDER")
                        .requestMatchers("/api/queues/*/complete-token").hasRole("PROVIDER")
                        .requestMatchers("/api/queues/*/activate").hasAnyRole("PROVIDER", "ADMIN")
                        .requestMatchers("/api/queues/*/deactivate").hasAnyRole("PROVIDER", "ADMIN")
                        // Places & services: admins and providers may create/change them; ownership is checked
                        // per item in the controllers (CatalogAccessService). GETs above stay public.
                        .requestMatchers(HttpMethod.GET, "/api/places/provider/**").hasRole("PROVIDER")
                        .requestMatchers(HttpMethod.GET, "/api/services/admin/**").hasRole("ADMIN")
                        // Admin endpoints
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/places").hasAnyRole("ADMIN", "PROVIDER")
                        .requestMatchers(HttpMethod.PUT, "/api/places/**").hasAnyRole("ADMIN", "PROVIDER")
                        .requestMatchers(HttpMethod.DELETE, "/api/places/**").hasAnyRole("ADMIN", "PROVIDER")
                        .requestMatchers(HttpMethod.POST, "/api/services").hasAnyRole("ADMIN", "PROVIDER")
                        .requestMatchers(HttpMethod.PUT, "/api/services/**").hasAnyRole("ADMIN", "PROVIDER")
                        .requestMatchers(HttpMethod.DELETE, "/api/services/**").hasAnyRole("ADMIN", "PROVIDER")
                        // All other requests need authentication
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                );

        // Not a @Bean on purpose: as a bean Spring Boot would also register it as a servlet filter
        http.addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}