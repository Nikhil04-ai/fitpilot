package com.project.fitness.dto;

import com.project.fitness.model.MealType;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Body for creating or editing a meal.
 *
 * <p>Every field is optional on edit — an omitted field keeps its current value, the
 * same partial-update contract {@code ActivityRequest} uses.
 */
@Data
public class MealRequest {

    /**
     * Ignored. The meal is always written against the authenticated user (AGENT.md §2);
     * kept so older clients that still send it don't get a validation failure.
     */
    private String userId;

    private String description;
    private MealType mealType;

    private Integer calories;
    private Double proteinGrams;
    private Double carbsGrams;
    private Double fatGrams;

    /** When it was eaten. Defaults to now — see MealLog.loggedAt for why it matters. */
    private LocalDateTime loggedAt;

    /**
     * Edits only: fields to empty. An update treats null as "keep what's there", so
     * without this a macro, once recorded, could be changed but never removed.
     * Accepts description, calories, proteinGrams, carbsGrams and fatGrams.
     */
    private Set<String> clear;
}
