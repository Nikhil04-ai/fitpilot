package com.project.fitness.controller;

import com.project.fitness.dto.LoginRequest;
import com.project.fitness.dto.RegisterRequest;
import com.project.fitness.dto.UserResponse;
import com.project.fitness.model.RefreshToken;
import com.project.fitness.model.User;
import com.project.fitness.security.CurrentUser;
import com.project.fitness.security.JwtUtils;
import com.project.fitness.service.PasswordResetService;
import com.project.fitness.service.RefreshTokenService;
import com.project.fitness.service.UserService;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtUtils jwtUtils;
    private final RefreshTokenService refreshTokenService;
    private final PasswordResetService passwordResetService;

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(userService.register(request));
    }

    /**
     * Login returns BOTH:
     *   accessToken  — 48 hours (sent in Authorization header)
     *   refreshToken — 30 days  (stored by client, sent to /refresh)
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest) {
        try {
            User user = userService.authenticate(loginRequest);
            String accessToken   = jwtUtils.generateToken(user.getId(), user.getRole().name(), user.getTokenVersion());
            RefreshToken refresh = refreshTokenService.createRefreshToken(user);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("accessToken",  accessToken);
            response.put("refreshToken", refresh.getToken());
            response.put("expiresIn",    "48 hours");
            response.put("user",         userService.mapToResponse(user));
            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid email or password"));
        }
    }

    /**
     * Exchange a valid refresh token for a new access token.
     * Old refresh token is automatically revoked (token rotation).
     *
     * POST /api/auth/refresh
     * Body: { "refreshToken": "uuid-here" }
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody RefreshRequest request) {
        try {
            return ResponseEntity.ok(refreshTokenService.refreshAccessToken(request.getRefreshToken()));
        } catch (RuntimeException e) {
            // Only the service's own "invalid / revoked / expired" refusals, which are
            // exact RuntimeExceptions, are a 401 (PLAN P4-7). Anything else is a real
            // fault: it used to be a 401 carrying its internal message, or a 500 from
            // Map.of when that message was null. GlobalExceptionHandler logs it and
            // answers with a generic 500 instead.
            if (e.getClass() != RuntimeException.class) throw e;
            return ResponseEntity.status(401).body(Map.of("error",
                    e.getMessage() != null ? e.getMessage() : "Invalid refresh token."));
        }
    }

    /**
     * Logout — revokes all refresh tokens for the user.
     * POST /api/auth/logout
     * Body: { "refreshToken": "..." }   (optional if a valid access token is sent)
     *
     * This route sits under the permitAll /api/auth/** prefix, so it used to accept
     * a userId from the body and revoke that user's sessions — meaning anyone could
     * log any account out, repeatedly, without credentials. The caller now has to
     * prove who they are: either a valid access token, or possession of the refresh
     * token being revoked. A userId in the body is ignored.
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(@RequestBody(required = false) LogoutRequest request) {
        String userId = CurrentUser.idOrNull();

        if (userId == null && request != null && request.getRefreshToken() != null) {
            userId = refreshTokenService.resolveUserId(request.getRefreshToken());
        }

        if (userId == null) {
            // Nothing to revoke server-side, but the client should still clear its
            // own storage — a failed logout must never leave someone stuck logged in.
            return ResponseEntity.ok(Map.of("message", "Logged out locally. No active session to revoke."));
        }

        refreshTokenService.revokeAll(userId);
        return ResponseEntity.ok(Map.of("message", "Logged out successfully. All sessions revoked."));
    }

    /**
     * Starts a password reset (PLAN P1-4).
     *
     * <p>Always 200 with the same body, whether or not the address has an account.
     * Answering differently would turn this into a free "does this person have an
     * account here" lookup for anyone with a list of email addresses.
     *
     * <p>Sits under the permitAll /api/auth/** prefix by necessity — someone who has
     * forgotten their password cannot authenticate first — and is covered by the
     * strict IP-keyed auth rate limit (PLAN P1-5).
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request.getEmail());
        return ResponseEntity.ok(Map.of(
                "message", "If an account exists for that address, a reset link is on its way."));
    }

    /**
     * Completes a password reset and ends every existing session for the account.
     *
     * <p>A bad, spent or expired token produces the same 400 message, so a caller
     * cannot use the response to learn which guessed tokens were once real.
     */
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.getToken(), request.getNewPassword());
        return ResponseEntity.ok(Map.of(
                "message", "Password updated. You have been signed out everywhere — log in with your new password."));
    }

    @Data static class RefreshRequest { private String refreshToken; }
    @Data static class LogoutRequest  { private String refreshToken; }
    @Data static class ForgotPasswordRequest { private String email; }
    @Data static class ResetPasswordRequest  { private String token; private String newPassword; }
}
