package com.project.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One movement as the client sends it — "Bench Press", 3 sets, 8 reps, 60kg. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExerciseRequest {
    private String name;
    private Integer sets;
    private Integer reps;
    private Double weightKg;
}
