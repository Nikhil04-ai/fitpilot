package com.project.fitness.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Per-movement strength records — one row per user per exercise.
 *
 * <p>{@link PersonalBest} is per {@link ActivityType}, so the best it could ever say
 * about lifting was "your longest weight-training session" (PLAN P0-6). This is the
 * record that actually means something: heaviest set, best estimated 1RM, biggest
 * single-session volume, for each movement by name.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "exercise_personal_best",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_exercise_pb_user_exercise",
               columnNames = {"user_id", "normalised_name"}))
public class ExercisePersonalBest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_exercise_pb_user"))
    @JsonIgnore
    private User user;

    /** Matching key — see {@link Exercise#normalise}. */
    @Column(name = "normalised_name")
    private String normalisedName;

    /** The spelling to show the user, from whichever entry last set a record. */
    private String displayName;

    /** Heaviest single load lifted, in kg, and the reps it was lifted for. */
    private Double bestWeightKg;
    private Integer bestWeightReps;

    /** Best Epley estimate across all sets — the headline strength number. */
    private Double bestEstimatedOneRepMax;

    /** Biggest sets x reps x weight in one entry. */
    private Double bestVolume;

    /**
     * Most reps in a single unloaded set.
     *
     * <p>Pull-ups and press-ups have no load to compare, so weight, 1RM and volume are
     * all null for them and a record card for one was three dashes. Reps are the thing
     * that actually progresses on a bodyweight movement, so that is what is recorded.
     * Only set when the entry carried no weight — otherwise 20 reps of an empty bar
     * would outrank 20 strict pull-ups.
     */
    private Integer bestBodyweightReps;

    private LocalDateTime achievedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
