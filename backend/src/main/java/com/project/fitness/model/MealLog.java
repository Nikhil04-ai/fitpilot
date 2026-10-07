package com.project.fitness.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One thing eaten (PLAN P2-1).
 *
 * <p>FitPilot measured exactly one side of the energy equation until this existed:
 * CalorieCalculatorService worked out what a workout burned, and nothing
 * recorded what went in. That is why {@link GoalType} was three activity-side weekly
 * counters and nothing about body weight — with no intake there is no deficit to
 * compute, so a weight goal could never be scored at all.
 *
 * <p><b>The numbers here are estimates and the app says so.</b> Calories come either
 * from the user or from the parser's guess at "two eggs and toast"; neither is a
 * measurement. {@link #aiEstimated} records which, so the UI can mark the difference
 * rather than presenting a generated number with the same authority as a typed one.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MealLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_meal_log_user"))
    @JsonIgnore
    private User user;

    /** What was eaten, as the user described it. Free text — always escaped on the way out. */
    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    private MealType mealType;

    private Integer calories;

    private Double proteinGrams;
    private Double carbsGrams;
    private Double fatGrams;

    /**
     * When the meal was eaten, not when it was typed in.
     *
     * <p>Same reasoning as {@code Activity.startTime}: every daily total, the net-calorie
     * series and the goal periods all bucket on this, so a meal backdated to yesterday
     * has to count against yesterday. A row saved without one is defaulted in the
     * service rather than left null for the aggregates to silently skip.
     */
    private LocalDateTime loggedAt;

    /**
     * True when the macros were guessed by the parser rather than entered by the user.
     *
     * <p>Kept because the two are not equally trustworthy and the difference is
     * invisible once the numbers are in a column. A user correcting an estimate clears
     * this flag — see {@code NutritionService.updateMeal}.
     */
    @Builder.Default
    private Boolean aiEstimated = false;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /**
     * Calories implied by the macros: 4/4/9 kcal per gram of protein/carb/fat.
     *
     * <p>Null unless all three are present. Used to fill in a calorie figure when the
     * parser returned macros without one, never to overrule a number that is already
     * there.
     */
    public Integer energyFromMacros() {
        if (proteinGrams == null || carbsGrams == null || fatGrams == null) return null;
        return (int) Math.round(proteinGrams * 4 + carbsGrams * 4 + fatGrams * 9);
    }
}
