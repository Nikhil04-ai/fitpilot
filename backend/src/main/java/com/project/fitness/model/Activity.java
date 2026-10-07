package com.project.fitness.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Entity
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Activity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_activity_user"))
    @JsonIgnore
    private User user;

    @Enumerated(EnumType.STRING)
    private ActivityType type;

    /**
     * Whatever else the parser or the client sent that has no column of its own.
     *
     * <p>Distance used to live in here too, which is why nothing could act on it: a
     * JSON blob can be displayed but not summed, compared or indexed, so "ran 5km" was
     * stored and then invisible to stats and to records alike (PLAN P0-2). The
     * structured part has been promoted to {@link #distanceKm}; this keeps the rest.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private Map<String, Object> additionalMetrics;

    /** Distance covered, in km. Null for anything that isn't measured by distance. */
    private Double distanceKm;

    private Integer duration;
    private Integer caloriesBurned;
    private LocalDateTime startTime;
    private LocalDateTime completedAt;
    private Integer difficultyRating;

    // Activity notes — text the user adds after a workout
    @Column(length = 1000)
    private String notes;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "activity", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    @Builder.Default
    private List<Recommendation> recommendations = new ArrayList<>();

    /**
     * The individual movements in this workout, in the order they were entered.
     *
     * <p>Empty for cardio. Eagerly ordered rather than sorted in Java so the list reads
     * back the way it was written without every caller having to remember to sort it.
     */
    @OneToMany(mappedBy = "activity", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    @JsonIgnore
    @Builder.Default
    private List<Exercise> exercises = new ArrayList<>();

    /** Replaces the whole exercise list, keeping the orphanRemoval contract intact. */
    public void replaceExercises(List<Exercise> replacements) {
        // Mutating the managed collection in place, rather than assigning a new list,
        // is what lets Hibernate delete the rows that were removed.
        this.exercises.clear();
        if (replacements == null) return;
        int index = 0;
        for (Exercise e : replacements) {
            e.setActivity(this);
            e.setOrderIndex(index++);
            this.exercises.add(e);
        }
    }

    /** Total load moved across every movement logged, in kg. Null when nothing is loaded. */
    public Double totalVolume() {
        double total = 0;
        boolean any = false;
        for (Exercise e : exercises) {
            Double v = e.volume();
            if (v != null) { total += v; any = true; }
        }
        return any ? Math.round(total * 10.0) / 10.0 : null;
    }
}
