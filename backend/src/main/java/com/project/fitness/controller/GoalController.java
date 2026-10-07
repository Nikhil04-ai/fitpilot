package com.project.fitness.controller;

import com.project.fitness.model.Goal;
import com.project.fitness.model.GoalType;
import com.project.fitness.security.CurrentUser;
import com.project.fitness.service.GoalService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/goals")
@RequiredArgsConstructor
public class GoalController {

    private final GoalService goalService;

    /** POST /api/goals — create a new goal */
    @PostMapping
    public ResponseEntity<Goal> createGoal(@RequestBody GoalRequest request) {
        // userId in the body is ignored — a goal always belongs to the caller.
        Goal created = goalService.createGoal(
                CurrentUser.id(),
                request.getType(),
                request.getTargetValue(),
                request.getDeadline()
        );
        return ResponseEntity.ok(created);
    }

    /** GET /api/goals/{userId} — all goals with progress percentages */
    @GetMapping("/{userId}")
    public ResponseEntity<List<Map<String, Object>>> getGoals(@PathVariable String userId) {
        return ResponseEntity.ok(goalService.getGoalsWithProgress(CurrentUser.requireSelf(userId)));
    }

    /** PUT /api/goals/{goalId} — update target or deadline */
    @PutMapping("/{goalId}")
    public ResponseEntity<Goal> updateGoal(
            @PathVariable String goalId,
            @RequestBody GoalUpdateRequest request) {
        Goal updated = goalService.updateGoal(CurrentUser.id(), goalId, request.getTargetValue(), request.getDeadline());
        return ResponseEntity.ok(updated);
    }

    /** DELETE /api/goals/{goalId} — remove a goal */
    @DeleteMapping("/{goalId}")
    public ResponseEntity<Void> deleteGoal(@PathVariable String goalId) {
        goalService.deleteGoal(CurrentUser.id(), goalId);
        return ResponseEntity.noContent().build();
    }

    @Data
    static class GoalRequest {
        private String userId;   // accepted for backwards compatibility, but ignored
        private GoalType type;
        private double targetValue;
        private LocalDate deadline;
    }

    @Data
    static class GoalUpdateRequest {
        private Double targetValue;
        private LocalDate deadline;
    }
}
