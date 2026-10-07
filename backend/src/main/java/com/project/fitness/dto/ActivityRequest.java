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
public class ActivityRequest {
    private String userId;
    private ActivityType type;
    private Map<String, Object> additionalMetrics;
    private Integer duration;
    private Integer caloriesBurned;
    private LocalDateTime startTime;
    private Integer difficultyRating;
    private String notes;   // Activity notes — added here

    /** Distance in km. Also accepted inside additionalMetrics, for the parser's sake. */
    private Double distanceKm;

    /**
     * The movements in this workout.
     *
     * <p>Null and an empty list mean different things on an edit: null leaves the
     * existing exercises alone (this endpoint is a partial update), an empty list
     * clears them.
     */
    private List<ExerciseRequest> exercises;

    /**
     * Edit only: re-derive {@code caloriesBurned} from type, duration and body weight,
     * replacing whatever is stored. Ignored when {@code caloriesBurned} is sent too.
     *
     * <p>Without it, an edit that omits calories keeps the stored figure, unless type or
     * duration changed and that figure was itself calculated (PLAN P4-2).
     */
    private Boolean recalculateCalories;
}
