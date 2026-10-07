package com.project.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One movement on the way out, with the two derived numbers computed server-side.
 *
 * <p>Volume and the 1RM estimate are sent rather than left to the client so the number
 * on screen is the same one the record was judged against — two implementations of
 * Epley is one too many.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExerciseResponse {
    private String id;
    private String name;
    private Integer sets;
    private Integer reps;
    private Double weightKg;
    private Integer orderIndex;

    /** sets x reps x weight, in kg. Null for bodyweight work. */
    private Double volume;

    /** Epley estimate. Null above 12 reps, where the formula stops being trustworthy. */
    private Double estimatedOneRepMax;

    /** True when this entry set or matched a record for the movement. */
    private Boolean isPersonalBest;
}
