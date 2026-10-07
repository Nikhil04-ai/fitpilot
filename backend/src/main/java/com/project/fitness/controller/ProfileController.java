package com.project.fitness.controller;

import com.project.fitness.dto.UserResponse;
import com.project.fitness.security.CurrentUser;
import com.project.fitness.service.ProfileService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    /**
     * Get profile.
     *
     * The {userId} segment is kept so existing clients and API.md stay valid, but it
     * is verified against the authenticated user rather than trusted — previously any
     * logged-in user could read (and via PUT below, rewrite) anyone else's profile
     * just by changing the id in the URL.
     */
    @GetMapping("/{userId}")
    public ResponseEntity<UserResponse> getProfile(@PathVariable String userId) {
        return ResponseEntity.ok(profileService.getProfile(CurrentUser.requireSelf(userId)));
    }

    /** Update name / weight / height / age */
    @PutMapping("/{userId}")
    public ResponseEntity<UserResponse> updateProfile(
            @PathVariable String userId,
            @RequestBody UpdateProfileRequest req) {
        return ResponseEntity.ok(profileService.updateProfile(
                CurrentUser.requireSelf(userId), req.getFirstName(), req.getLastName(),
                req.getWeight(), req.getHeight(), req.getAge()));
    }

    /** Change password */
    @PutMapping("/{userId}/password")
    public ResponseEntity<Map<String, String>> changePassword(
            @PathVariable String userId,
            @RequestBody PasswordRequest req) {
        return ResponseEntity.ok(profileService.changePassword(
                CurrentUser.requireSelf(userId), req.getCurrentPassword(), req.getNewPassword()));
    }

    @Data static class UpdateProfileRequest {
        private String firstName;
        private String lastName;
        private Double weight;
        private Double height;
        private Integer age;
    }

    @Data static class PasswordRequest {
        private String currentPassword;
        private String newPassword;
    }
}
