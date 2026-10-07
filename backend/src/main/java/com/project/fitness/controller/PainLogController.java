package com.project.fitness.controller;

import com.project.fitness.model.BodyPart;
import com.project.fitness.model.PainLog;
import com.project.fitness.security.AccountGuard;
import com.project.fitness.security.CurrentUser;
import com.project.fitness.service.PainAnalysisService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/pain-log")
@RequiredArgsConstructor
public class PainLogController {

    private final PainAnalysisService painAnalysisService;
    private final AccountGuard accountGuard;

    /** POST /api/pain-log — log pain after a workout */
    @PostMapping
    public ResponseEntity<PainLog> logPain(@RequestBody PainLogRequest request) {
        PainLog saved = painAnalysisService.logPain(
                CurrentUser.id(),
                request.getActivityId(),
                request.getBodyPart(),
                request.getSeverity(),
                request.getDescription()
        );
        return ResponseEntity.ok(saved);
    }

    /** GET /api/pain-log/{userId}?page=&size= — pain history, newest first, paged */
    @GetMapping("/{userId}")
    public ResponseEntity<Page<PainLog>> getPainHistory(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(painAnalysisService.getPainHistory(accountGuard.requireSelf(userId),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                        Sort.by(Sort.Direction.DESC, "loggedAt"))));
    }

    /** GET /api/pain-log/patterns/{userId} — detected patterns and warnings */
    @GetMapping("/patterns/{userId}")
    public ResponseEntity<List<String>> getPatterns(@PathVariable String userId) {
        return ResponseEntity.ok(painAnalysisService.detectPatterns(accountGuard.requireSelf(userId)));
    }

    @Data
    static class PainLogRequest {
        private String userId;       // accepted for backwards compatibility, but ignored
        private String activityId;   // optional — link pain to a specific workout
        private BodyPart bodyPart;
        private int severity;        // 1–5
        private String description;
    }
}
