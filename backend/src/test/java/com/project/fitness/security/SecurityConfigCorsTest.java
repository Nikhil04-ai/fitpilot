package com.project.fitness.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Which browser origins may call the API (PLAN P5-14). */
@DisplayName("SecurityConfig CORS origins")
class SecurityConfigCorsTest {

    @Test
    @DisplayName("locally: localhost, 127.0.0.1, file:// and FRONTEND_URL")
    void localAllowsDevOrigins() {
        assertThat(SecurityConfig.allowedOrigins("https://name.github.io", true))
                .containsExactly("http://localhost:*", "http://127.0.0.1:*", "null", "https://name.github.io");
    }

    @Test
    @DisplayName("locally with no FRONTEND_URL: the dev origins only")
    void localWithoutFrontendUrl() {
        assertThat(SecurityConfig.allowedOrigins("", true))
                .containsExactly("http://localhost:*", "http://127.0.0.1:*", "null");
    }

    @Test
    @DisplayName("prod: FRONTEND_URL and nothing else")
    void prodAllowsOnlyTheFrontend() {
        assertThat(SecurityConfig.allowedOrigins(" https://name.github.io ", false))
                .containsExactly("https://name.github.io");
    }

    @Test
    @DisplayName("a FRONTEND_URL with a path is reduced to the origin a browser sends")
    void pathIsDropped() {
        assertThat(SecurityConfig.originOf("https://name.github.io/fitpilot/")).isEqualTo("https://name.github.io");
        assertThat(SecurityConfig.originOf("https://example.com:8443/app")).isEqualTo("https://example.com:8443");
        assertThat(SecurityConfig.originOf("https://name.github.io")).isEqualTo("https://name.github.io");
    }

    @Test
    @DisplayName("something that isn't a URL is passed through as written")
    void unparseableIsKept() {
        assertThat(SecurityConfig.originOf("https://*.example.com")).isEqualTo("https://*.example.com");
    }
}
