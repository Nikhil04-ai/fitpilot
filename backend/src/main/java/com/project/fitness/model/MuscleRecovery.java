package com.project.fitness.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Feature 4 — Muscle Recovery Tracker
 * Tracks when each muscle group was last worked so we can warn about overtraining.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MuscleRecovery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_muscle_recovery_user"))
    @JsonIgnore
    private User user;

    @Enumerated(EnumType.STRING)
    private MuscleGroup muscleGroup;

    private LocalDateTime lastWorkedAt;

    // Standard recovery window: 48 hours
    @Builder.Default
    private int recommendedRestHours = 48;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
