package com.project.fitness.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Feature 3 — Personal Best Tracker
 * One record per user per activity type. Updated whenever a new record is set.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PersonalBest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_personal_best_user"))
    @JsonIgnore
    private User user;

    @Enumerated(EnumType.STRING)
    private ActivityType activityType;

    private Integer bestDuration;    // minutes
    private Integer bestCalories;    // kcal

    /**
     * Furthest single session, in km. Added with PLAN P1-1: distance was being parsed
     * and stored in a JSON blob, so the one number a runner actually competes against
     * could never become a record.
     */
    private Double bestDistanceKm;

    private LocalDateTime achievedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
