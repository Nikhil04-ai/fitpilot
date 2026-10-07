package com.project.fitness.service;

import com.project.fitness.model.PasswordResetToken;
import com.project.fitness.model.User;
import com.project.fitness.repository.PasswordResetTokenRepository;
import com.project.fitness.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Forgotten-password recovery (PLAN P1-4).
 *
 * <p>Most of these are about what the endpoint must <em>not</em> reveal or allow,
 * because that is where a reset flow goes wrong.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordResetService Tests")
class PasswordResetServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordResetTokenRepository tokenRepository;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private EmailService emailService;

    // The real encoder: "the stored hash actually verifies" is part of what is
    // being checked, and a mock would assert nothing.
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private PasswordResetService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(userRepository, tokenRepository,
                passwordEncoder, refreshTokenService, emailService);
        ReflectionTestUtils.setField(service, "ttlMinutes", 30);
        ReflectionTestUtils.setField(service, "frontendUrl", "https://fitpilot.example");
        ReflectionTestUtils.setField(service, "minPasswordLength", 8);

        user = User.builder().id("user-1").email("someone@example.com")
                .password(passwordEncoder.encode("oldPassword123")).build();
    }

    /** Captures the raw token out of the link the email would have carried. */
    private String capturedLinkToken() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).send(anyString(), anyString(), body.capture());
        String html = body.getValue();
        int start = html.indexOf("?reset=") + "?reset=".length();
        int end = html.indexOf('"', start);
        return html.substring(start, end);
    }

    // ── Requesting a reset ───────────────────────────────────────────────────

    @Test
    @DisplayName("stores only a hash of the token, never the token itself")
    void storesOnlyTheHash() {
        when(userRepository.findByEmail("someone@example.com")).thenReturn(user);
        when(tokenRepository.findByUserIdAndUsedAtIsNull("user-1")).thenReturn(List.of());

        service.requestReset("someone@example.com");

        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(saved.capture());

        String raw = capturedLinkToken();
        assertThat(raw).isNotBlank();
        // A leaked backup must not contain anything a person can click.
        assertThat(saved.getValue().getTokenHash()).isNotEqualTo(raw);
        assertThat(saved.getValue().getTokenHash()).hasSize(64);   // SHA-256, hex
        assertThat(saved.getValue().getExpiresAt()).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("an unknown address is silently ignored, not reported")
    void unknownAddressRevealsNothing() {
        when(userRepository.findByEmail(anyString())).thenReturn(null);

        // No throw, no token, no email. The controller says the same thing either way,
        // so the endpoint cannot be used to test whether an address has an account.
        service.requestReset("nobody@example.com");

        verify(tokenRepository, never()).save(any());
        verify(emailService, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a blank address does nothing rather than erroring")
    void blankAddressIsIgnored() {
        service.requestReset("   ");
        service.requestReset(null);
        verify(tokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("requesting again invalidates the link already sent")
    void newRequestInvalidatesTheOldLink() {
        when(userRepository.findByEmail("someone@example.com")).thenReturn(user);
        PasswordResetToken outstanding = PasswordResetToken.builder()
                .user(user).tokenHash("old-hash")
                .expiresAt(LocalDateTime.now().plusMinutes(20)).build();
        when(tokenRepository.findByUserIdAndUsedAtIsNull("user-1")).thenReturn(List.of(outstanding));

        service.requestReset("someone@example.com");

        // An old email left in an inbox, or forwarded, stops working.
        assertThat(outstanding.getUsedAt()).isNotNull();
    }

    @Test
    @DisplayName("an address stored with its original casing is still found")
    void fallsBackToUnnormalisedEmail() {
        when(userRepository.findByEmail("someone@example.com")).thenReturn(null);
        when(userRepository.findByEmail("SomeOne@Example.com")).thenReturn(user);
        when(tokenRepository.findByUserIdAndUsedAtIsNull("user-1")).thenReturn(List.of());

        service.requestReset("SomeOne@Example.com");

        verify(tokenRepository).save(any());
    }

    @Test
    @DisplayName("a failed send does not fail the request")
    void emailFailureIsNotFatal() {
        when(userRepository.findByEmail("someone@example.com")).thenReturn(user);
        when(tokenRepository.findByUserIdAndUsedAtIsNull("user-1")).thenReturn(List.of());
        when(emailService.send(anyString(), anyString(), anyString())).thenReturn(false);

        // Without a mail key configured this is the normal local path — it must not
        // become a 500 for someone trying to recover their account.
        service.requestReset("someone@example.com");

        verify(tokenRepository).save(any());
    }

    @Test
    @DisplayName("a failed send with email configured is not fatal either")
    void configuredEmailFailureIsNotFatal() {
        when(userRepository.findByEmail("someone@example.com")).thenReturn(user);
        when(tokenRepository.findByUserIdAndUsedAtIsNull("user-1")).thenReturn(List.of());
        when(emailService.isConfigured()).thenReturn(true);
        when(emailService.send(anyString(), anyString(), anyString())).thenReturn(false);

        // The provider refused (EmailService logs why). The request still succeeds, and
        // the live link is not written to the log on this path.
        service.requestReset("someone@example.com");

        verify(tokenRepository).save(any());
    }

    // ── Completing a reset ───────────────────────────────────────────────────

    @Test
    @DisplayName("a valid token sets the new password, spends the token and ends all sessions")
    void resetSucceeds() {
        when(userRepository.findByEmail("someone@example.com")).thenReturn(user);
        when(tokenRepository.findByUserIdAndUsedAtIsNull("user-1")).thenReturn(List.of());
        service.requestReset("someone@example.com");

        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(saved.capture());
        PasswordResetToken token = saved.getValue();
        token.setUser(user);

        when(tokenRepository.findByTokenHash(token.getTokenHash())).thenReturn(Optional.of(token));

        service.resetPassword(capturedLinkToken(), "brandNewPassword1");

        assertThat(passwordEncoder.matches("brandNewPassword1", user.getPassword())).isTrue();
        assertThat(token.getUsedAt()).as("token must be spent").isNotNull();
        // Someone resets precisely when they think another person has their password;
        // leaving that person's session alive would defeat the point.
        verify(refreshTokenService).revokeAll("user-1");
    }

    @Test
    @DisplayName("an unknown token is refused with the same message as a spent one")
    void unknownTokenRefused() {
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword("made-up-token", "brandNewPassword1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("expired or already been used");
    }

    @Test
    @DisplayName("a spent token cannot be replayed")
    void usedTokenRefused() {
        PasswordResetToken spent = PasswordResetToken.builder()
                .user(user).tokenHash("hash")
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .usedAt(LocalDateTime.now().minusMinutes(1)).build();
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(spent));

        assertThatThrownBy(() -> service.resetPassword("whatever", "brandNewPassword1"))
                .hasMessageContaining("expired or already been used");
        assertThat(passwordEncoder.matches("oldPassword123", user.getPassword())).isTrue();
    }

    @Test
    @DisplayName("an expired token is refused even though it was never used")
    void expiredTokenRefused() {
        PasswordResetToken stale = PasswordResetToken.builder()
                .user(user).tokenHash("hash")
                .expiresAt(LocalDateTime.now().minusMinutes(1)).build();
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(stale));

        assertThatThrownBy(() -> service.resetPassword("whatever", "brandNewPassword1"))
                .hasMessageContaining("expired or already been used");
    }

    @Test
    @DisplayName("a short password is rejected before the token is spent")
    void shortPasswordRejected() {
        assertThatThrownBy(() -> service.resetPassword("some-token", "short"))
                .hasMessageContaining("at least 8 characters");

        // Rejected on the way in, so a typo does not burn the link.
        verify(tokenRepository, never()).findByTokenHash(anyString());
    }

    @Test
    @DisplayName("a missing token is rejected without a lookup")
    void blankTokenRejected() {
        assertThatThrownBy(() -> service.resetPassword("  ", "brandNewPassword1"))
                .hasMessageContaining("not valid");
        verify(tokenRepository, never()).findByTokenHash(anyString());
    }

    @Test
    @DisplayName("two requests never produce the same token")
    void tokensAreUnpredictable() {
        when(userRepository.findByEmail("someone@example.com")).thenReturn(user);
        when(tokenRepository.findByUserIdAndUsedAtIsNull("user-1")).thenReturn(List.of());

        service.requestReset("someone@example.com");
        service.requestReset("someone@example.com");

        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository, org.mockito.Mockito.times(2)).save(saved.capture());

        assertThat(saved.getAllValues().get(0).getTokenHash())
                .isNotEqualTo(saved.getAllValues().get(1).getTokenHash());
    }
}
