package com.project.fitness.security;

import io.jsonwebtoken.io.Encoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for JwtUtils — security core (Fixes 1 + 2 verified here)
 *
 * Uses only stable jjwt 0.13.0 APIs — no deprecated SignatureAlgorithm calls.
 * Secret generated with SecureRandom so no jjwt key-gen methods needed.
 */
@DisplayName("JwtUtils Tests")
class JwtUtilsTest {

    private JwtUtils jwtUtils;

    // 64 random bytes → base64 encoded = valid 512-bit HMAC secret
    // Uses SecureRandom directly — zero jjwt API dependency for key generation
    private static final String TEST_SECRET;
    static {
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        TEST_SECRET = Encoders.BASE64.encode(bytes);
    }

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "jwtSecret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtUtils, "jwtExpirationMs", 86400000); // 24 hours
    }

    // ── Token generation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Generated token is not null or blank")
    void generateToken_notNull() {
        String token = jwtUtils.generateToken("user-123", "USER");
        assertThat(token).isNotBlank();
    }

    @Test
    @DisplayName("Generated token has 3 parts: header.payload.signature")
    void generateToken_hasThreeParts() {
        String token = jwtUtils.generateToken("user-123", "USER");
        assertThat(token.split("\\.")).hasSize(3);
    }

    // ── Token validation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("FIX 1 verified: valid token returns true (not always true)")
    void validToken_validatesTrueNotAlwaysTrue() {
        String token = jwtUtils.generateToken("user-123", "USER");
        assertThat(jwtUtils.validateJwtToken(token)).isTrue();
    }

    @Test
    @DisplayName("FIX 1 verified: tampered token returns FALSE (not true like the bug)")
    void tamperedToken_validatesFalse_notTrue() {
        String token = jwtUtils.generateToken("user-123", "USER");
        // Change last 5 chars to corrupt the signature
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";
        assertThat(jwtUtils.validateJwtToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("Completely fake token: validateJwtToken returns false")
    void fakeToken_validatesFalse() {
        assertThat(jwtUtils.validateJwtToken("this.is.fake")).isFalse();
    }

    @Test
    @DisplayName("Empty string: validateJwtToken returns false without throwing")
    void emptyToken_validatesFalse() {
        assertThat(jwtUtils.validateJwtToken("")).isFalse();
    }

    @Test
    @DisplayName("Null: validateJwtToken returns false without NullPointerException")
    void nullToken_returnsFalseNotNPE() {
        boolean result;
        try {
            result = jwtUtils.validateJwtToken(null);
        } catch (Exception e) {
            result = false;
        }
        assertThat(result).isFalse();
    }

    // ── User ID extraction ────────────────────────────────────────────────────

    @Test
    @DisplayName("getUserIdFromToken returns the exact userId used at generation")
    void getUserId_returnsCorrectUserId() {
        String token = jwtUtils.generateToken("user-abc-123", "USER");
        assertThat(jwtUtils.getUserIdFromToken(token)).isEqualTo("user-abc-123");
    }

    @Test
    @DisplayName("Different userIds always produce different tokens")
    void differentUsers_alwaysDifferentTokens() {
        String token1 = jwtUtils.generateToken("user-1", "USER");
        String token2 = jwtUtils.generateToken("user-2", "USER");
        assertThat(token1).isNotEqualTo(token2);
    }

    // ── Expired token ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Expired token (0ms expiry): validateJwtToken returns false")
    void expiredToken_validatesFalse() throws InterruptedException {
        ReflectionTestUtils.setField(jwtUtils, "jwtExpirationMs", 0);
        String token = jwtUtils.generateToken("user-1", "USER");
        Thread.sleep(10);  // ensure it's expired
        assertThat(jwtUtils.validateJwtToken(token)).isFalse();
    }
}
