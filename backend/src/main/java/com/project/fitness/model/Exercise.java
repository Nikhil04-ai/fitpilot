package com.project.fitness.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * One movement inside a workout — "Bench Press, 3 x 8 @ 60kg".
 *
 * <p>Before this existed, {@link Activity} modelled a workout as duration + calories,
 * which describes a run perfectly and a lifting session not at all: there was a
 * {@code WEIGHT_TRAINING} activity type and no way to record a single lift, so the
 * only "records" the app could hold for it were longest session and most calories —
 * neither of which anyone training for strength cares about (PLAN P1-1, P0-6).
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Exercise {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_exercise_activity"))
    @JsonIgnore
    private Activity activity;

    /** As the user typed it — "Bench Press", "bench press", "Barbell Bench Press". */
    private String name;

    /**
     * Lower-cased, collapsed-whitespace form of {@link #name}, used to decide whether
     * two entries are the same movement.
     *
     * <p>Stored rather than derived on read because records are looked up by it: the
     * database can index this, and grouping on {@code LOWER(name)} at query time
     * cannot use an index. Kept in sync by {@link #setName}.
     */
    private String normalisedName;

    private Integer sets;
    private Integer reps;

    /** Load per rep, in kg. Null for bodyweight movements. */
    private Double weightKg;

    /** Position within the workout, so the list reads back in the order it was entered. */
    private Integer orderIndex;

    @CreationTimestamp
    private LocalDateTime createdAt;

    /** Setting the name always sets the normalised form — they must not drift apart. */
    public void setName(String name) {
        this.name = name;
        this.normalisedName = normalise(name);
    }

    public static String normalise(String name) {
        if (name == null) return null;
        String trimmed = name.trim().toLowerCase(Locale.ROOT).replaceAll("\s+", " ");
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Total load moved: sets x reps x weight.
     *
     * <p>Null unless all three are present — a bodyweight set has no load to total, and
     * returning 0 for it would drag a volume record down rather than leave it alone.
     */
    public Double volume() {
        if (sets == null || reps == null || weightKg == null) return null;
        return sets * reps * weightKg;
    }

    /**
     * Estimated one-rep max, by the Epley formula: {@code w x (1 + reps/30)}.
     *
     * <p>Epley rather than a measured 1RM because nobody logs true singles regularly,
     * and it is the comparison that makes 5x5 @ 80kg and 3x10 @ 60kg rankable against
     * each other at all. It is an estimate and drifts high above ~10 reps, which is why
     * sets beyond 12 reps are not used to claim a strength record.
     */
    public Double estimatedOneRepMax() {
        if (weightKg == null || reps == null || reps < 1 || weightKg <= 0) return null;
        if (reps > 12) return null;
        if (reps == 1) return weightKg;
        return Math.round(weightKg * (1 + reps / 30.0) * 10.0) / 10.0;
    }

    /** The Lombok builder bypasses setName, so normalise on the way into the database too. */
    @PrePersist
    @PreUpdate
    void syncNormalisedName() {
        this.normalisedName = normalise(this.name);
    }
}
