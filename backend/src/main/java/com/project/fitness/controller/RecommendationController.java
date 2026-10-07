package com.project.fitness.controller;

import com.project.fitness.exceptions.AccountNotFoundException;
import com.project.fitness.model.Activity;
import com.project.fitness.model.Recommendation;
import com.project.fitness.model.User;
import com.project.fitness.repository.ActivityRepository;
import com.project.fitness.repository.UserRepository;
import com.project.fitness.security.AccountGuard;
import com.project.fitness.security.CurrentUser;
import com.project.fitness.service.RecommendationService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import java.util.List;

@RestController
@RequestMapping("/api/recommendation")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final AccountGuard accountGuard;
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

        // findById(null) throws IllegalArgumentException, which surfaced as a 500.
        if (request.getActivityId() == null || request.getActivityId().isBlank()) {
            throw new RuntimeException("activityId is required: the recommendation is about one workout.");
        }
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

    /** GET /api/recommendation/user/{userId}?page=&size= — newest first, paged (PLAN P4-19) */
    @GetMapping("/user/{userId}")
    public ResponseEntity<Page<Recommendation>> getUserRecommendation(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(recommendationService.getUserRecommendation(accountGuard.requireSelf(userId),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                        Sort.by(Sort.Direction.DESC, "createdAt"))));
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
