package com.project.fitness.dto;

import com.project.fitness.model.MealType;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class MealResponse {

    private String id;
    private String userId;

    private String description;
    private MealType mealType;

    private Integer calories;
    private Double proteinGrams;
    private Double carbsGrams;
    private Double fatGrams;

    private LocalDateTime loggedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * True when the numbers were estimated by the parser rather than entered.
     *
     * <p>Surfaced deliberately: the UI marks these, because a generated calorie count
     * for "two eggs and toast" is a guess and presenting it identically to a figure
     * read off a packet would be the app quietly overstating what it knows.
     */
    private Boolean aiEstimated;

    /** Goals completed or breached by this meal, ready to show. Null when none. */
    private List<String> goalAchievements;

    /** Populated on the natural-language path when the parser had to guess. Null otherwise. */
    private String parseNote;
}
