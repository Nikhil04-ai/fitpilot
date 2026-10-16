package com.project.fitness.service;

import com.project.fitness.model.RefreshToken;
import com.project.fitness.model.User;
import com.project.fitness.repository.RefreshTokenRepository;
import com.project.fitness.repository.UserRepository;
import com.project.fitness.security.JwtUtils;
import com.project.fitness.security.TokenHash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

    /** What the database holds for the token the client knows as "raw-1". */
    private static final String HASH_1 = TokenHash.of("raw-1");

    private RefreshToken live() {
        return RefreshToken.builder().id("rt-1").user(user).tokenHash(HASH_1)
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
    @DisplayName("only the token's hash is stored; the raw value is returned once, for the client (P5-13)")
    void createRefreshToken_storesOnlyTheHash() {
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        when(refreshTokenRepository.save(saved.capture())).thenAnswer(i -> i.getArgument(0));

        String raw = refreshTokenService.createRefreshToken(user);

        assertThat(saved.getValue().getTokenHash())
                .isEqualTo(TokenHash.of(raw))
                .isNotEqualTo(raw)
                .matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("a missing or blank refresh token is invalid without a lookup")
    void refresh_missingToken_isInvalid() {
        for (String missing : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> refreshTokenService.refreshAccessToken(missing))
                    .hasMessageContaining("Invalid refresh token");
            assertThat(refreshTokenService.resolveUserId(missing)).isNull();
        }
        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    @DisplayName("a refresh retires only the token it was given")
    void refresh_revokesOnlyThePresentedToken() {
        when(refreshTokenRepository.findByTokenHash(HASH_1)).thenReturn(Optional.of(live()));
        when(refreshTokenRepository.revokeIfActive("rt-1")).thenReturn(1);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(i -> i.getArgument(0));
        when(jwtUtils.generateToken(any(), any(), anyInt())).thenReturn("access");

        assertThat(refreshTokenService.refreshAccessToken("raw-1")).containsKey("refreshToken");

        verify(refreshTokenRepository).revokeIfActive("rt-1");
        verify(refreshTokenRepository, never()).revokeAllByUserId(anyString());
    }

    @Test
    @DisplayName("losing a race on the same token is a revoked token, and issues nothing")
    void refresh_lostRace_isRejected() {
        when(refreshTokenRepository.findByTokenHash(HASH_1)).thenReturn(Optional.of(live()));
        when(refreshTokenRepository.revokeIfActive("rt-1")).thenReturn(0);   // the other request won

        assertThatThrownBy(() -> refreshTokenService.refreshAccessToken("raw-1"))
                .hasMessageContaining("revoked");
        verify(refreshTokenRepository, never()).save(any());
        verifyNoInteractions(jwtUtils);
    }

    @Test
    @DisplayName("logout ends every session: refresh tokens revoked and access tokens retired")
    void endAllSessions_revokesAndRetires() {
        refreshTokenService.endAllSessions("user-1");

        verify(refreshTokenRepository).revokeAllByUserId("user-1");
        verify(userRepository).retireIssuedTokens("user-1");
    }

    // ── resolveUserId: what proves identity at logout ──────────────────────────

    @Test
    @DisplayName("a live refresh token identifies its owner")
    void resolveUserId_liveToken() {
        when(refreshTokenRepository.findByTokenHash(HASH_1)).thenReturn(Optional.of(live()));
        assertThat(refreshTokenService.resolveUserId("raw-1")).isEqualTo("user-1");
    }

    @Test
    @DisplayName("a revoked refresh token identifies nobody")
    void resolveUserId_revokedToken() {
        RefreshToken revoked = live();
        revoked.setRevoked(true);
        when(refreshTokenRepository.findByTokenHash(HASH_1)).thenReturn(Optional.of(revoked));
        assertThat(refreshTokenService.resolveUserId("raw-1")).isNull();
    }

    @Test
    @DisplayName("an expired refresh token identifies nobody")
    void resolveUserId_expiredToken() {
        RefreshToken expired = live();
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(refreshTokenRepository.findByTokenHash(HASH_1)).thenReturn(Optional.of(expired));
        assertThat(refreshTokenService.resolveUserId("raw-1")).isNull();
    }

    @Test
    @DisplayName("the daily purge removes spent tokens as of now")
    void purge_deletesSpentTokens() {
        refreshTokenService.purgeSpentTokens();
        verify(refreshTokenRepository).deleteSpent(any(LocalDateTime.class));
    }
}
