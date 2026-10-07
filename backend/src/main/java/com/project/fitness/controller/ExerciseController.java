package com.project.fitness.controller;

import com.project.fitness.model.ExercisePersonalBest;
import com.project.fitness.repository.ExercisePersonalBestRepository;
import com.project.fitness.security.AccountGuard;
import com.project.fitness.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Per-movement strength records (PLAN P1-1).
 *
 * <p>Separate from /api/personal-bests, which is per activity type — the two answer
 * different questions and merging them would mean a response where half the fields are
 * always null.
 */
@RestController
@RequestMapping("/api/exercises")
@RequiredArgsConstructor
public class ExerciseController {

    private final ExercisePersonalBestRepository exercisePersonalBestRepository;
    private final AccountGuard accountGuard;

    /** Heaviest set, best estimated 1RM and best session volume, per movement. */
    @GetMapping("/personal-bests/{userId}")
    public ResponseEntity<List<ExercisePersonalBest>> getExercisePersonalBests(@PathVariable String userId) {
        return ResponseEntity.ok(exercisePersonalBestRepository
                .findByUserIdOrderByBestEstimatedOneRepMaxDesc(accountGuard.requireSelf(userId)));
    }
}
