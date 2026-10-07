package com.project.fitness.service;

import com.project.fitness.model.PasswordResetToken;
import com.project.fitness.model.User;
import com.project.fitness.repository.PasswordResetTokenRepository;
import com.project.fitness.repository.UserRepository;
import com.project.fitness.security.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Forgotten-password recovery (PLAN P1-4).
 *
 * <p>Until now the only way to change a password was {@code PUT /api/profile/{id}/password},
 * which requires being logged in already — so a forgotten password was a dead account
 * with no path back.
 *
 * <p>The security properties that matter here, and why:
 *
 * <ul>
 *   <li><b>No account enumeration.</b> {@link #requestReset} behaves identically for a
 *       known and an unknown address. An endpoint that answers "no such user" is a
 *       free membership oracle for anyone with a list of emails.</li>
 *   <li><b>Only the hash is stored.</b> A live reset token is a password equivalent;
 *       see {@link PasswordResetToken}.</li>
 *   <li><b>Single use, short lived.</b> Spent on first successful reset, and expired
 *       after {@code app.password-reset.ttl-minutes}.</li>
 *   <li><b>One outstanding grant.</b> Requesting again invalidates the previous link,
 *       so an old email forwarded or left in an inbox stops working.</li>
 *   <li><b>All sessions end.</b> A reset revokes every refresh token. Someone resets
 *       their password precisely when they think somebody else has it, and leaving
 *       the intruder's session alive would defeat the point.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final EmailService emailService;

    @Value("${app.password-reset.ttl-minutes:30}")
    private int ttlMinutes;

    /**
     * Where the reset link points. The frontend reads the token off the query string.
     *
     * <p>Blank rather than absent when FRONTEND_URL is unset, because
     * application.properties always defines the key - so the fallback is applied in
     * {@link #resetLinkBase()} rather than by the annotation default, which would
     * never fire.
     */
    @Value("${app.frontend-url:}")
    private String frontendUrl;

    private static final String DEV_FRONTEND_URL = "http://localhost:5500";

    @Value("${app.password-reset.min-length:8}")
    private int minPasswordLength;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Starts a reset for the given address, if it belongs to an account.
     *
     * <p>Returns nothing on purpose: the caller has the same thing to say either way.
     */
    @Transactional
    public void requestReset(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) return;

        String email = rawEmail.trim().toLowerCase();
        User user = userRepository.findByEmail(email);
        // Accounts created before emails were normalised are stored as typed.
        if (user == null) user = userRepository.findByEmail(rawEmail.trim());

        if (user == null) {
            log.info("Password reset requested for an address with no account.");
            return;
        }

        // Any link already out there stops working the moment a new one is asked for.
        for (PasswordResetToken outstanding : tokenRepository.findByUserIdAndUsedAtIsNull(user.getId())) {
            outstanding.setUsedAt(LocalDateTime.now());
            tokenRepository.save(outstanding);
        }

        String rawToken = generateToken();
        tokenRepository.save(PasswordResetToken.builder()
                .user(user)
                .tokenHash(hash(rawToken))
                .expiresAt(LocalDateTime.now().plusMinutes(ttlMinutes))
                .build());

        String link = resetLinkBase() + "/index.html?reset=" + rawToken;
        boolean sent = emailService.send(user.getEmail(), "Reset your FitPilot password", emailBody(link));

        if (!sent && !emailService.isConfigured()) {
            // The local-development path: without a mail key the link goes to the log
            // so the flow is still completable.
            log.warn("Password reset link for {} (not emailed): {}", user.getEmail(), link);
        } else if (!sent) {
            // Email is configured and delivery failed (EmailService has logged why).
            // The link is deliberately NOT logged here: it is a password equivalent,
            // and a production log is read by more people than the account's owner.
            log.error("Password reset email to {} failed; the user will need to request another.", user.getEmail());
        }
    }

    /**
     * Completes a reset.
     *
     * @throws RuntimeException with a message safe to show, which
     *         GlobalExceptionHandler turns into a 400.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new RuntimeException("This reset link is not valid. Request a new one.");
        }
        if (newPassword == null || newPassword.length() < minPasswordLength) {
            throw new RuntimeException("Your new password must be at least " + minPasswordLength + " characters.");
        }
        // Before the token lookup, like the minimum: a valid link must not be spent
        // on a password that can't be hashed.
        PasswordPolicy.requireHashable(newPassword);

        Optional<PasswordResetToken> found = tokenRepository.findByTokenHash(hash(rawToken));

        // One message for "never existed", "already used" and "expired". Telling them
        // apart tells an attacker which guessed tokens were once real.
        PasswordResetToken token = found.filter(PasswordResetToken::isUsable).orElseThrow(() ->
                new RuntimeException("This reset link has expired or already been used. Request a new one."));

        User user = token.getUser();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        token.setUsedAt(LocalDateTime.now());
        tokenRepository.save(token);

        // Anyone already signed in as this user is signed out, including whoever
        // prompted the reset.
        refreshTokenService.revokeAll(user.getId());

        log.info("Password reset completed for user {}", user.getId());
    }

    /**
     * Clears out spent and expired grants once a day.
     *
     * <p>Nothing reads them after use, and without this the table only ever grows.
     */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeStaleTokens() {
        int removed = tokenRepository.deleteExpiredOrUsed(LocalDateTime.now());
        if (removed > 0) log.info("Purged {} spent or expired password reset tokens.", removed);
    }

    /** The deployed frontend origin, or the local dev server when none is configured. */
    private String resetLinkBase() {
        String base = (frontendUrl == null || frontendUrl.isBlank()) ? DEV_FRONTEND_URL : frontendUrl.trim();
        return base.replaceAll("/+$", "");
    }

    /** 256 bits from a CSPRNG, URL-safe so it survives being pasted into a link. */
    private String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256, not BCrypt.
     *
     * <p>BCrypt is deliberately slow to make guessing a human-chosen password
     * expensive. This value is 256 random bits, so there is nothing to guess and the
     * slowness would only buy an attacker a way to tie up threads by submitting
     * garbage tokens. A plain digest is the right tool for a high-entropy secret.
     */
    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; if it is missing, nothing here is safe.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String emailBody(String link) {
        return """
               <div style="font-family:system-ui,-apple-system,Segoe UI,sans-serif;max-width:480px">
                 <h2 style="margin-bottom:8px">Reset your FitPilot password</h2>
                 <p style="color:#444;line-height:1.6">
                   Someone asked to reset the password on this account. Use the link below
                   within %d minutes. If it wasn't you, ignore this email — nothing has
                   changed and your current password still works.
                 </p>
                 <p style="margin:24px 0">
                   <a href="%s" style="background:#0EA5FF;color:#fff;padding:12px 20px;
                      border-radius:8px;text-decoration:none;font-weight:600">Reset password</a>
                 </p>
                 <p style="color:#888;font-size:12px;word-break:break-all">%s</p>
               </div>
               """.formatted(ttlMinutes, link, link);
    }
}
