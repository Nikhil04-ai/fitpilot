package com.project.fitness.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A plan is a named collection of planned days (e.g. "7-Day Beginner" or "30-Day Strength").
 * Each plan has many WorkoutPlanDay entries.
 */
@Entity
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class WorkoutPlan {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String name;          // "7-Day Beginner Cardio"
    private String description;
    private Integer totalDays;    // 7, 14, 30 etc.
    private String difficulty;    // BEGINNER / INTERMEDIATE / ADVANCED
    private String goal;          // WEIGHT_LOSS / MUSCLE_GAIN / ENDURANCE / FLEXIBILITY

    // null = system template; userId = custom user plan
    private String createdByUserId;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<WorkoutPlanDay> days = new ArrayList<>();

    @CreationTimestamp
    private LocalDateTime createdAt;
}
