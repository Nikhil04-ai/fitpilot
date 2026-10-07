package com.project.fitness.controller;

import com.project.fitness.model.MuscleRecovery;
import com.project.fitness.security.CurrentUser;
import com.project.fitness.service.MuscleRecoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/recovery")
@RequiredArgsConstructor
public class RecoveryController {

    private final MuscleRecoveryService muscleRecoveryService;

    /** GET /api/recovery/status/{userId} — all muscle groups with recovery status */
    @GetMapping("/status/{userId}")
    public ResponseEntity<List<MuscleRecovery>> getRecoveryStatus(@PathVariable String userId) {
        return ResponseEntity.ok(muscleRecoveryService.getRecoveryStatus(CurrentUser.requireSelf(userId)));
    }
}
