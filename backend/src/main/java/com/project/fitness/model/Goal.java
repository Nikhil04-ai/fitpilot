package com.project.fitness.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Feature 12 — Goal Setting and Progress Tracking
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Goal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_goal_user"))
    @JsonIgnore
    private User user;

    @Enumerated(EnumType.STRING)
    private GoalType type;

    private Double targetValue;
    @Builder.Default
    private Double currentValue = 0.0;
    private LocalDate deadline;
    @Builder.Default
    private Boolean isCompleted = false;

    /**
     * Where this goal started, for the types that move towards a target rather than
     * accumulate towards one (PLAN P2-1).
     *
     * <p>A body-weight goal cannot be scored without it. "72kg" alone says nothing about
     * whether you are cutting or bulking, and a progress bar needs a zero point — 80 →
     * 72 is 50% done at 76kg, which is unknowable from the target and today's weight.
     * Captured from the latest body-metrics entry when the goal is created.
     *
     * <p>Null on every accumulating goal, where progress starts from 0 by definition.
     */
    private Double startValue;

    /**
     * First day of the period `currentValue` is counting.
     *
     * Progress used to accumulate forever: hit "5 workouts per week" once and the goal
     * stayed complete at 5/5 for good, and a calorie goal's total only ever climbed.
     * GoalService rolls this forward and resets the count when a new period starts.
     *
     * Which period that is now comes from {@link GoalType#period()} — Monday for the
     * weekly types, today for the daily nutrition ones (PLAN P2-1). It stays null for
     * TARGET_WEIGHT, which holds a standing value and has nothing to reset.
     *
     * Null on goals created before this field existed — treated as "needs a roll-over".
     */
    private LocalDate periodStart;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
