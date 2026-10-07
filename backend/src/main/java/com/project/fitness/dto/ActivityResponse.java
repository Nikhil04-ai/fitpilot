package com.project.fitness.dto;

import com.project.fitness.model.ActivityType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActivityResponse {
    private String id;
    private String userId;
    private ActivityType type;
    private Map<String, Object> additionalMetrics;
    private Integer duration;
    private Integer caloriesBurned;
    private LocalDateTime startTime;
    private LocalDateTime completedAt;
    private Integer difficultyRating;
    private String notes;

    // Feature P1-1 — structured metrics
    private Double distanceKm;
    private List<ExerciseResponse> exercises;
    /** Sum of every exercise's sets x reps x weight, in kg. */
    private Double totalVolume;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Feature 2 — Streak
    private Integer currentStreak;
    private Integer longestStreak;

    // Feature 3 — Personal Best
    private Boolean isPersonalBest;
    private String personalBestMessage;

    // Feature 4 — Muscle recovery warnings
    private List<String> muscleRecoveryWarnings;

    // Feature 8 — Comeback protocol
    private Map<String, Object> comebackPlan;

    // Feature 12 — Goal achievements
    private List<String> goalAchievements;

    // Feature 13 — Progression suggestion. Driven by logged load where there is any,
    // and only by self-reported difficulty when there isn't (PLAN P1-1).
    private String progressionSuggestion;

    /** Per-movement records broken by this workout, e.g. "Bench Press — 65kg x 5". */
    private List<String> exercisePersonalBests;
}
