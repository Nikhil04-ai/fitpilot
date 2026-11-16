package com.project.fitness.service;

import com.project.fitness.dto.UserResponse;
import com.project.fitness.exceptions.AccountNotFoundException;
import com.project.fitness.model.User;
import com.project.fitness.repository.UserRepository;
import com.project.fitness.security.PasswordPolicy;
import com.project.fitness.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Transactional
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserService userService;
    private final RefreshTokenService refreshTokenService;
    private final JwtUtils jwtUtils;
    private final BodyMetricsService bodyMetricsService;

    /** Update any combination of profile fields */
    public UserResponse updateProfile(String userId, String firstName, String lastName,
                                      Double weight, Double height, Integer age) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        // All three before any is applied, so a bad age can't leave a new weight behind
        // (PLAN P4-17).
        InputChecks.weightKg(weight);
        InputChecks.heightCm(height);
        InputChecks.age(age);

        boolean bodyChanged = (weight != null && !weight.equals(user.getWeight()))
                || (height != null && !height.equals(user.getHeight()));

        if (firstName != null && !firstName.isBlank()) user.setFirstName(firstName);
        if (lastName  != null && !lastName.isBlank())  user.setLastName(lastName);
        if (weight    != null) user.setWeight(weight);
        if (height    != null) user.setHeight(height);
        if (age       != null) user.setAge(age);

        User saved = userRepository.save(user);
        // The profile is where the app lets a person change their weight, and it wrote
        // no weigh-in: the weight chart could never fill from the UI, and a weight goal
        // kept reading the last weigh-in while Health showed the new weight (found
        // 2026-11-16). A change of weight or height now records one, as PUT
        // /api/health/metrics does. Saving the same values again, or only a name, doesn't.
        if (bodyChanged) bodyMetricsService.recordWeighIn(saved);
        return userService.mapToResponse(saved);
    }

    /**
     * Change password — requires the current password for verification.
     *
     * <p>Every session ends, as on a password reset: a device signed in with the old
     * password, perhaps by whoever learned it, must not stay signed in (PLAN P4-3). The
     * caller proved the current password, so it gets a fresh pair back and stays in.
     * Access tokens already issued stop working too, through the account's token version.
     */
    public Map<String, String> changePassword(String userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        // A current password BCrypt couldn't have hashed can't be the right one, and
        // handing it to the encoder throws rather than returning false (PLAN P4-7).
        if (currentPassword == null
                || currentPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > PasswordPolicy.MAX_BYTES
                || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new RuntimeException("Current password is incorrect.");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new RuntimeException("New password must be at least 8 characters.");
        }
        PasswordPolicy.requireNotBlank(newPassword);
        PasswordPolicy.requireHashable(newPassword);

        user.setPassword(passwordEncoder.encode(newPassword));
        // Access tokens too, not only refresh tokens: one already issued stays valid until
        // it expires otherwise. Bumped before the new token below is minted, so the
        // caller's own fresh token carries the new version and stays good.
        user.retireIssuedTokens();
        userRepository.save(user);

        refreshTokenService.revokeAll(userId);
        return Map.of(
                "message", "Password changed successfully. Other devices have been signed out.",
                "accessToken", jwtUtils.generateToken(user.getId(), user.getRole().name(), user.getTokenVersion()),
                "refreshToken", refreshTokenService.createRefreshToken(user));
    }

    public UserResponse getProfile(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));
        return userService.mapToResponse(user);
    }
}
