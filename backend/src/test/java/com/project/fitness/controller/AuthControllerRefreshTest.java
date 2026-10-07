package com.project.fitness.controller;

import com.project.fitness.security.JwtUtils;
import com.project.fitness.service.PasswordResetService;
import com.project.fitness.service.RefreshTokenService;
import com.project.fitness.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * {@code POST /api/auth/refresh} error handling (PLAN P4-7).
 *
 * <p>It turned every exception into a 401 carrying {@code e.getMessage()}. A null message
 * made {@code Map.of} throw, so the refresh itself became a 500, and a real fault's
 * internal message was shown to the client. Only the service's own refusals are a 401 now.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController — refresh errors")
class AuthControllerRefreshTest {

    @Mock private UserService userService;
    @Mock private JwtUtils jwtUtils;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private PasswordResetService passwordResetService;
    @InjectMocks private AuthController authController;

    private final AuthController.RefreshRequest request = new AuthController.RefreshRequest();

    @BeforeEach
    void setUp() {
        request.setRefreshToken("rt");
    }

    @Test
    @DisplayName("a refusal from the service is a 401 with its message")
    void refusalIs401() {
        when(refreshTokenService.refreshAccessToken("rt"))
                .thenThrow(new RuntimeException("Refresh token has expired. Please log in again."));

        ResponseEntity<?> response = authController.refresh(request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "Refresh token has expired. Please log in again."));
    }

    @Test
    @DisplayName("a refusal with no message is still a 401, not a crash in Map.of")
    void nullMessageIs401() {
        when(refreshTokenService.refreshAccessToken("rt")).thenThrow(new RuntimeException());

        ResponseEntity<?> response = authController.refresh(request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "Invalid refresh token."));
    }

    @Test
    @DisplayName("a real fault is rethrown for GlobalExceptionHandler, not shown as a 401")
    void realFaultIsRethrown() {
        when(refreshTokenService.refreshAccessToken("rt"))
                .thenThrow(new IllegalStateException("connection pool exhausted"));

        assertThatThrownBy(() -> authController.refresh(request))
                .isInstanceOf(IllegalStateException.class);
    }
}
