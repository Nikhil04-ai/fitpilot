package com.project.fitness.controller;

import com.project.fitness.exceptions.AccountNotFoundException;
import com.project.fitness.model.Activity;
import com.project.fitness.model.Recommendation;
import com.project.fitness.model.User;
import com.project.fitness.repository.ActivityRepository;
import com.project.fitness.repository.UserRepository;
import com.project.fitness.security.CurrentUser;
import com.project.fitness.service.RecommendationService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/recommendation")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final UserRepository userRepository;
    private final ActivityRepository activityRepository;

    /**
     * Manual trigger for AI recommendation (useful for testing).
     * Auto-trigger happens every 3rd activity in ActivityService.
     * Body: { "userId": "...", "activityId": "..." }
     */
    @PostMapping("/generate")
    public ResponseEntity<Recommendation> generateRecommendation(
            @RequestBody GenerateRequest request) {

        String userId = CurrentUser.id();   // userId in the body is ignored

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        Activity activity = activityRepository.findById(request.getActivityId())
                .orElseThrow(() -> new RuntimeException("Activity not found: " + request.getActivityId()));

        // Generating a recommendation reads the activity's full history, so the
        // activity has to belong to the caller — not just exist.
        if (!activity.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("You don't have access to this activity.");
        }

        Recommendation recommendation = recommendationService.generateRecommendation(user, activity);
        return ResponseEntity.ok(recommendation);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Recommendation>> getUserRecommendation(@PathVariable String userId) {
        return ResponseEntity.ok(recommendationService.getUserRecommendation(CurrentUser.requireSelf(userId)));
    }

    @GetMapping("/activity/{activityId}")
    public ResponseEntity<List<Recommendation>> getActivityRecommendation(@PathVariable String activityId) {
        return ResponseEntity.ok(recommendationService.getActivityRecommendation(CurrentUser.id(), activityId));
    }

    @Data
    static class GenerateRequest {
        private String userId;       // accepted for backwards compatibility, but ignored
        private String activityId;
    }
}
