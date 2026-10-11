package com.project.fitness.service;

import com.project.fitness.model.RefreshToken;
import com.project.fitness.model.User;
import com.project.fitness.repository.RefreshTokenRepository;
import com.project.fitness.repository.UserRepository;
import com.project.fitness.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Refresh tokens, one per signed-in device.
 *
 * <p>Each login adds a session and leaves the others alone; each refresh retires only
 * the token it was given (PLAN P4-3). Issuing a token used to revoke every other token
 * the user held, so signing in on a phone, or one tab renewing its session, signed out
 * every other device. Everything ends together only on logout, a password change and a
 * password reset — the three places that call {@link #revokeAll}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtUtils jwtUtils;

    private static final int REFRESH_TOKEN_DAYS = 30;

    /** Creates and persists a new refresh token for a user. Other sessions are untouched. */
    @Transactional
    public RefreshToken createRefreshToken(User user) {
        RefreshToken token = RefreshToken.builder()
                .user(user)
                .token(RefreshToken.generate())
                .expiresAt(LocalDateTime.now().plusDays(REFRESH_TOKEN_DAYS))
                .build();

        return refreshTokenRepository.save(token);
    }

    /**
     * Validates the refresh token and issues a fresh pair.
     * The token presented is revoked (rotation — prevents replay); no other is.
     */
    @Transactional
    public Map<String, Object> refreshAccessToken(String rawRefreshToken) {
        RefreshToken stored = refreshTokenRepository.findByToken(rawRefreshToken)
                .orElseThrow(() -> new RuntimeException("Invalid refresh token."));

        if (stored.getRevoked()) {
            throw new RuntimeException("Refresh token has been revoked. Please log in again.");
        }
        if (stored.isExpired()) {
            throw new RuntimeException("Refresh token has expired. Please log in again.");
        }
        // Claimed atomically: of two requests racing on one token, only one gets here.
        if (refreshTokenRepository.revokeIfActive(stored.getId()) == 0) {
            throw new RuntimeException("Refresh token has been revoked. Please log in again.");
        }

        User user = stored.getUser();

        // Issue new access token
        String newAccessToken = jwtUtils.generateToken(user.getId(), user.getRole().name(), user.getTokenVersion());

        RefreshToken newRefreshToken = createRefreshToken(user);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accessToken", newAccessToken);
        response.put("refreshToken", newRefreshToken.getToken());
        response.put("expiresIn", "48 hours");
        response.put("refreshTokenExpiresIn", "30 days");
        return response;
    }

    /**
     * Resolves the owner of a refresh token, or null if the token is unknown.
     * Used by logout so a caller with no (or an expired) access token can still
     * end their own session by proving possession of their refresh token.
     */
    @Transactional(readOnly = true)
    public String resolveUserId(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) return null;
        return refreshTokenRepository.findByToken(rawRefreshToken)
                .map(t -> t.getUser().getId())
                .orElse(null);
    }

    /** Revoke all tokens for a user: logout, password change and password reset. */
    @Transactional
    public void revokeAll(String userId) {
        refreshTokenRepository.revokeAllByUserId(userId);
    }

    /**
     * Deletes revoked and expired tokens once a day.
     *
     * <p>Nothing can use them again. Now that a login no longer revokes the user's
     * other tokens, every sign-in adds a row, and without this the table only grows.
     */
    @Scheduled(cron = "0 45 3 * * *")
    @Transactional
    public void purgeSpentTokens() {
        int removed = refreshTokenRepository.deleteSpent(LocalDateTime.now());
        if (removed > 0) log.info("Purged {} spent refresh tokens", removed);
    }
}
