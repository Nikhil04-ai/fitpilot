package com.project.fitness.controller;

import com.project.fitness.model.SleepLog;
import com.project.fitness.security.AccountGuard;
import com.project.fitness.security.CurrentUser;
import com.project.fitness.service.BodyMetricsService;
import com.project.fitness.service.SleepService;
import com.project.fitness.service.WaterIntakeService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * BMI + body metrics history  →  /api/health/metrics
 * Water intake tracker        →  /api/health/water
 * Sleep tracker               →  /api/health/sleep
 */
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final BodyMetricsService bodyMetricsService;
    private final AccountGuard accountGuard;
    private final WaterIntakeService waterIntakeService;
    private final SleepService sleepService;

    // ── BMI / Body Metrics ──────────────────────────────────────────────────

    @PutMapping("/metrics/{userId}")
    public ResponseEntity<Map<String, Object>> updateMetrics(
            @PathVariable String userId,
            @RequestBody MetricsRequest req) {
        return ResponseEntity.ok(bodyMetricsService.updateMetrics(CurrentUser.requireSelf(userId), req.getWeight(), req.getHeight()));
    }

    @GetMapping("/metrics/{userId}")
    public ResponseEntity<Map<String, Object>> getMetrics(@PathVariable String userId) {
        return ResponseEntity.ok(bodyMetricsService.getMetrics(CurrentUser.requireSelf(userId)));
    }

    /** Every weigh-in, newest first, paged. /metrics embeds only the latest 100. */
    @GetMapping("/metrics/{userId}/history")
    public ResponseEntity<Page<Map<String, Object>>> getMetricsHistory(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(bodyMetricsService.getHistory(accountGuard.requireSelf(userId),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                        Sort.by(Sort.Direction.DESC, "loggedAt"))));
    }

    // ── Water ───────────────────────────────────────────────────────────────

    @PostMapping("/water/{userId}")
    public ResponseEntity<Map<String, Object>> logWater(
            @PathVariable String userId,
            @RequestBody WaterRequest req) {
        return ResponseEntity.ok(waterIntakeService.logWater(CurrentUser.requireSelf(userId), req.getAmountMl(), req.getDate()));
    }

    @GetMapping("/water/{userId}/today")
    public ResponseEntity<Map<String, Object>> getWaterToday(@PathVariable String userId) {
        return ResponseEntity.ok(waterIntakeService.getDailySummary(CurrentUser.requireSelf(userId), LocalDate.now()));
    }

    @GetMapping("/water/{userId}/week")
    public ResponseEntity<List<Map<String, Object>>> getWaterWeek(@PathVariable String userId) {
        return ResponseEntity.ok(waterIntakeService.getWeeklyHistory(CurrentUser.requireSelf(userId)));
    }

    // ── Sleep ───────────────────────────────────────────────────────────────

    @PostMapping("/sleep/{userId}")
    public ResponseEntity<SleepLog> logSleep(
            @PathVariable String userId,
            @RequestBody SleepRequest req) {
        return ResponseEntity.ok(sleepService.logSleep(
                CurrentUser.requireSelf(userId), req.getSleepDate(), req.getHoursSlept(),
                req.getQualityRating(), req.getBedtime(), req.getWakeTime(), req.getNotes()));
    }

    @GetMapping("/sleep/{userId}/analysis")
    public ResponseEntity<Map<String, Object>> getSleepAnalysis(@PathVariable String userId) {
        return ResponseEntity.ok(sleepService.getWeeklySleepAnalysis(accountGuard.requireSelf(userId)));
    }

    /** Each logged night paired with the next day's training — the scatter chart's data. */
    @GetMapping("/sleep/{userId}/performance")
    public ResponseEntity<Map<String, Object>> getSleepPerformance(
            @PathVariable String userId,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(sleepService.getSleepPerformance(CurrentUser.requireSelf(userId), days));
    }

    /** Sleep history, most recent night first, paged. */
    @GetMapping("/sleep/{userId}/history")
    public ResponseEntity<Page<SleepLog>> getSleepHistory(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(sleepService.getHistory(accountGuard.requireSelf(userId),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                        Sort.by(Sort.Direction.DESC, "sleepDate"))));
    }

    // ── Request bodies ───────────────────────────────────────────────────────

    @Data static class MetricsRequest {
        private Double weight;
        private Double height;
    }

    @Data static class WaterRequest {
        private double amountMl;
        private LocalDate date;  // null = today
    }

    @Data static class SleepRequest {
        private LocalDate sleepDate;
        private double hoursSlept;
        private int qualityRating;
        private LocalDateTime bedtime;
        private LocalDateTime wakeTime;
        private String notes;
    }
}
