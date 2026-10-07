package com.project.fitness.controller;

import com.project.fitness.dto.MealRequest;
import com.project.fitness.dto.MealResponse;
import com.project.fitness.security.CurrentUser;
import com.project.fitness.service.NutritionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Calorie and macro intake (PLAN P2-1)  →  /api/nutrition
 *
 * <p>Every route takes its identity from the JWT. There is no {@code {userId}} segment
 * anywhere here, and the {@code userId} field on {@link MealRequest} is ignored —
 * a food log is about as personal as this app's data gets.
 */
@RestController
@RequestMapping("/api/nutrition")
@RequiredArgsConstructor
public class NutritionController {

    private final NutritionService nutritionService;

    /** Columns a client is allowed to sort history by — anything else is rejected. */
    private static final java.util.Set<String> SORTABLE =
            java.util.Set.of("loggedAt", "createdAt", "calories");

    @PostMapping("/meals")
    public ResponseEntity<MealResponse> logMeal(@RequestBody MealRequest request) {
        return ResponseEntity.ok(nutritionService.logMeal(CurrentUser.id(), request));
    }

    /**
     * Free-text logging — "two eggs, toast and a coffee".
     *
     * <p>Sits under the AI rate-limit tier, not the standard one: every call costs a
     * Gemini request against a shared quota. See RateLimitingFilter, where this path is
     * matched explicitly for the same reason {@code /api/activities/natural} is.
     */
    @PostMapping("/meals/natural")
    public ResponseEntity<MealResponse> logMealNatural(@RequestBody NaturalMealRequest request) {
        return ResponseEntity.ok(
                nutritionService.parseAndLog(CurrentUser.id(), request.getDescription()));
    }

    @PutMapping("/meals/{mealId}")
    public ResponseEntity<MealResponse> updateMeal(
            @PathVariable String mealId,
            @RequestBody MealRequest request) {
        return ResponseEntity.ok(
                nutritionService.updateMeal(CurrentUser.id(), mealId, request));
    }

    @DeleteMapping("/meals/{mealId}")
    public ResponseEntity<Void> deleteMeal(@PathVariable String mealId) {
        nutritionService.deleteMeal(CurrentUser.id(), mealId);
        return ResponseEntity.noContent().build();
    }

    /** One day's intake, burn and net. Defaults to today. */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getDailySummary(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(nutritionService.getDailySummary(CurrentUser.id(), date));
    }

    /** Paginated meal history, newest first. */
    @GetMapping("/meals")
    public ResponseEntity<Page<MealResponse>> getHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "loggedAt,desc") String sort) {

        String[] parts = sort.split(",");
        String property = SORTABLE.contains(parts[0]) ? parts[0] : "loggedAt";
        Sort.Direction direction = parts.length > 1 && parts[1].equalsIgnoreCase("asc")
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(direction, property));

        return ResponseEntity.ok(nutritionService.getHistory(CurrentUser.id(), pageable));
    }

    /** Intake vs burn per day — the net-calorie chart. */
    @GetMapping("/daily")
    public ResponseEntity<List<Map<String, Object>>> getDailySeries(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(nutritionService.getDailySeries(CurrentUser.id(), days));
    }

    @Data static class NaturalMealRequest { private String description; }
}
