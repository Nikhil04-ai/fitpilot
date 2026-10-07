package com.project.fitness.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Tracks which plan a user is on and how far they've gotten */
@Entity
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class UserPlanEnrollment {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_enrollment_user"))
    @JsonIgnore
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_enrollment_plan"))
    private WorkoutPlan plan;

    private LocalDate startDate;

    @Builder.Default
    private Integer currentDay = 1;

    @Builder.Default
    private Integer completedDays = 0;

    @Builder.Default
    private Boolean isActive = true;

    private LocalDate completedAt;

    /**
     * The day the user last marked complete. Used to reject a second "complete day"
     * on the same date — without it, repeated clicks advanced the plan a day at a
     * time and a 7-day plan could be run to the end in a single session.
     */
    private LocalDate lastCompletedDate;

    @CreationTimestamp
    private LocalDateTime enrolledAt;
}
