package com.project.fitness.security;

import com.project.fitness.config.RateLimitingFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitingFilter rateLimitingFilter;

    // Set as FRONTEND_URL env var on Render, e.g. https://yourname.github.io
    // Blank locally — only localhost origins are allowed then (see below).
    @Value("${app.frontend-url:}")
    private String frontendUrl;

    // False under the prod profile (PLAN P5-14): the deployed API answers browsers
    // on FRONTEND_URL only.
    @Value("${app.cors.allow-local-origins:true}")
    private boolean allowLocalOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Without these, Spring's default for a stateless API is 403 for BOTH
            // "no/expired token" and "token fine but not allowed". The frontend's
            // silent-refresh in app.js triggers on 401 only, so a 403 for an expired
            // token means the refresh never fires and the person just sees errors.
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(restAuthenticationEntryPoint())
                    .accessDeniedHandler(restAccessDeniedHandler()))
            .authorizeHttpRequests(auth -> auth
                    // CORS preflight requests must always be allowed through, unauthenticated
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    .requestMatchers(
                            "/swagger-ui.html",
                            "/swagger-ui/**",
                            "/v3/api-docs/**").permitAll()
                    .anyRequest().authenticated())
            // Rate limiter runs BEFORE JWT filter so even unauthenticated requests are limited
            .addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * CORS — lets the frontend call this API from the browser.
     * Allows whatever FRONTEND_URL is set to (e.g. your GitHub Pages URL), so the
     * deployed frontend can reach the deployed backend without editing this file again.
     * Outside the prod profile it also allows localhost (for local dev) and file://
     * (Origin: null).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(allowedOrigins(frontendUrl, allowLocalOrigins));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "X-RateLimit-Remaining", "Retry-After"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    static List<String> allowedOrigins(String frontendUrl, boolean allowLocal) {
        List<String> origins = new ArrayList<>();
        if (allowLocal) {
            origins.add("http://localhost:*");
            origins.add("http://127.0.0.1:*");
            origins.add("null"); // browsers send Origin: null when an HTML file is opened directly (file://)
        }
        if (frontendUrl != null && !frontendUrl.isBlank()) {
            origins.add(originOf(frontendUrl.trim()));
        }
        return origins;
    }

    /**
     * The scheme, host and port of a URL: what a browser sends as {@code Origin}.
     *
     * <p>FRONTEND_URL is also where reset links point, so it can carry a path. A GitHub
     * Pages project site lives at {@code https://name.github.io/repo}, but its pages send
     * {@code Origin: https://name.github.io}, which the whole URL would never match.
     * Anything that doesn't parse as a URL is passed through as written.
     */
    static String originOf(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getScheme() == null || uri.getHost() == null) return url;
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (URISyntaxException e) {
            return url;
        }
    }

    /** 401 — no token, expired token, or invalid token. Frontend refreshes and retries. */
    @Bean
    public AuthenticationEntryPoint restAuthenticationEntryPoint() {
        return (request, response, authException) ->
                writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "Authentication required. Please log in again.");
    }

    /** 403 — authenticated, but not allowed to touch this resource. Refreshing won't help. */
    @Bean
    public AccessDeniedHandler restAccessDeniedHandler() {
        return (request, response, accessDeniedException) ->
                writeJsonError(response, HttpServletResponse.SC_FORBIDDEN,
                        accessDeniedException.getMessage() != null
                                ? accessDeniedException.getMessage()
                                : "You don't have access to this resource.");
    }

    private static void writeJsonError(HttpServletResponse response, int status, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
