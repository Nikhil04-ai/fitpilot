package com.project.fitness.service;

import com.project.fitness.model.RefreshToken;
import com.project.fitness.model.User;
import com.project.fitness.repository.RefreshTokenRepository;
import com.project.fitness.repository.UserRepository;
import com.project.fitness.security.JwtUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * RefreshTokenService — what a new or rotated token does to the others (PLAN P4-3).
 * SessionRevocationIntegrationTest covers the same ground over HTTP; this pins the parts
 * HTTP can't show, like losing a race on a token.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RefreshTokenService")
class RefreshTokenServiceTest {

    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private JwtUtils jwtUtils;

    @InjectMocks private RefreshTokenService refreshTokenService;

    private final User user = User.builder().id("user-1").build();

    private RefreshToken live() {
        return RefreshToken.builder().id("rt-1").user(user).token("raw-1")
                .expiresAt(LocalDateTime.now().plusDays(1)).build();
    }

    @Test
    @DisplayName("issuing a token leaves the user's other sessions alone")
    void createRefreshToken_revokesNothing() {
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(i -> i.getArgument(0));

        refreshTokenService.createRefreshToken(user);

        verify(refreshTokenRepository, never()).revokeAllByUserId(anyString());
    }

    @Test
    @DisplayName("a refresh retires only the token it was given")
    void refresh_revokesOnlyThePresentedToken() {
        when(refreshTokenRepository.findByToken("raw-1")).thenReturn(Optional.of(live()));
        when(refreshTokenRepository.revokeIfActive("rt-1")).thenReturn(1);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(i -> i.getArgument(0));
        when(jwtUtils.generateToken(any(), any())).thenReturn("access");

        assertThat(refreshTokenService.refreshAccessToken("raw-1")).containsKey("refreshToken");

        verify(refreshTokenRepository).revokeIfActive("rt-1");
        verify(refreshTokenRepository, never()).revokeAllByUserId(anyString());
    }

    @Test
    @DisplayName("losing a race on the same token is a revoked token, and issues nothing")
    void refresh_lostRace_isRejected() {
        when(refreshTokenRepository.findByToken("raw-1")).thenReturn(Optional.of(live()));
        when(refreshTokenRepository.revokeIfActive("rt-1")).thenReturn(0);   // the other request won

        assertThatThrownBy(() -> refreshTokenService.refreshAccessToken("raw-1"))
                .hasMessageContaining("revoked");
        verify(refreshTokenRepository, never()).save(any());
        verifyNoInteractions(jwtUtils);
    }

    @Test
    @DisplayName("the daily purge removes spent tokens as of now")
    void purge_deletesSpentTokens() {
        refreshTokenService.purgeSpentTokens();
        verify(refreshTokenRepository).deleteSpent(any(LocalDateTime.class));
    }
}
