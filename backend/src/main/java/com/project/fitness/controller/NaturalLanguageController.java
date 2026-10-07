package com.project.fitness.controller;

import com.project.fitness.dto.ActivityResponse;
import com.project.fitness.security.CurrentUser;
import com.project.fitness.service.NaturalLanguageActivityService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Feature 9 — Natural Language Activity Logging
 * POST /api/activities/natural
 * Body: { "userId": "...", "description": "I ran 5km in 30 minutes this morning" }
 */
@RestController
@RequestMapping("/api/activities/natural")
@RequiredArgsConstructor
public class NaturalLanguageController {

    private final NaturalLanguageActivityService naturalLanguageActivityService;

    @PostMapping
    public ResponseEntity<ActivityResponse> logNatural(@RequestBody NaturalLogRequest request) {
        ActivityResponse response = naturalLanguageActivityService
                .parseAndSave(CurrentUser.id(), request.getDescription());
        return ResponseEntity.ok(response);
    }

    @Data
    static class NaturalLogRequest {
        private String userId;       // accepted for backwards compatibility, but ignored
        private String description;
    }
}
