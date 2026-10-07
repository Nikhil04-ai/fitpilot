package com.project.fitness.controller;

import com.project.fitness.model.WorkoutPlan;
import com.project.fitness.security.AccountGuard;
import com.project.fitness.security.CurrentUser;
import com.project.fitness.service.WorkoutPlanService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
public class WorkoutPlanController {

    private final WorkoutPlanService workoutPlanService;
    private final AccountGuard accountGuard;

    /** Browse all system plans */
    @GetMapping
    public ResponseEntity<List<WorkoutPlan>> getAllPlans() {
        return ResponseEntity.ok(workoutPlanService.getAllSystemPlans());
    }

    /** Full detail of one plan including all days */
    @GetMapping("/{planId}")
    public ResponseEntity<WorkoutPlan> getPlanDetail(@PathVariable String planId) {
        return ResponseEntity.ok(workoutPlanService.getPlanDetail(planId));
    }

    /** Enroll in a plan */
    @PostMapping("/{planId}/enroll")
    public ResponseEntity<Map<String, Object>> enroll(
            @PathVariable String planId,
            @RequestBody EnrollRequest req) {
        return ResponseEntity.ok(workoutPlanService.enrollUser(CurrentUser.id(), planId));
    }

    /** Get active plan + today's workout */
    @GetMapping("/active/{userId}")
    public ResponseEntity<Map<String, Object>> getActivePlan(@PathVariable String userId) {
        return ResponseEntity.ok(workoutPlanService.getActivePlan(accountGuard.requireSelf(userId)));
    }

    /** Mark today's workout as done — advances to next day */
    @PostMapping("/active/{userId}/complete-day")
    public ResponseEntity<Map<String, Object>> completeDay(@PathVariable String userId) {
        return ResponseEntity.ok(workoutPlanService.completeTodaysDay(CurrentUser.requireSelf(userId)));
    }

    @Data
    static class EnrollRequest { private String userId; }  // ignored — the caller enrolls themselves
}
