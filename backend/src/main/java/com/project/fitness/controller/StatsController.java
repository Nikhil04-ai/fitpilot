package com.project.fitness.controller;

import com.project.fitness.security.CurrentUser;
import com.project.fitness.service.PlateauDetectorService;
import com.project.fitness.service.StatsService;
import com.project.fitness.service.TimingAnalysisService;
import com.project.fitness.model.ActivityType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;
    private final TimingAnalysisService timingAnalysisService;
    private final PlateauDetectorService plateauDetectorService;

    @GetMapping("/weekly/{userId}")
    public ResponseEntity<Map<String, Object>> getWeeklyStats(@PathVariable String userId) {
        return ResponseEntity.ok(statsService.getWeeklyStats(CurrentUser.requireSelf(userId)));
    }

    @GetMapping("/monthly/{userId}")
    public ResponseEntity<Map<String, Object>> getMonthlyStats(@PathVariable String userId) {
        return ResponseEntity.ok(statsService.getMonthlyStats(CurrentUser.requireSelf(userId)));
    }

    /**
     * Per-day totals for the volume chart. Zero days are included, so the series is
     * always exactly `days` long and a rest day is visible as a gap rather than
     * skipped over.
     */
    @GetMapping("/daily/{userId}")
    public ResponseEntity<List<Map<String, Object>>> getDailySeries(
            @PathVariable String userId,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(statsService.getDailySeries(CurrentUser.requireSelf(userId), days));
    }

    @GetMapping("/all-time/{userId}")
    public ResponseEntity<Map<String, Object>> getAllTimeStats(@PathVariable String userId) {
        return ResponseEntity.ok(statsService.getAllTimeStats(CurrentUser.requireSelf(userId)));
    }

    @GetMapping("/timing/{userId}")
    public ResponseEntity<Map<String, Object>> getTimingAnalysis(@PathVariable String userId) {
        return ResponseEntity.ok(timingAnalysisService.analyseTimingForUser(CurrentUser.requireSelf(userId)));
    }

    @GetMapping("/plateau/{userId}")
    public ResponseEntity<Map<String, Object>> checkPlateau(
            @PathVariable String userId,
            @RequestParam ActivityType activityType) {
        return ResponseEntity.ok(plateauDetectorService.checkPlateau(CurrentUser.requireSelf(userId), activityType));
    }
}
