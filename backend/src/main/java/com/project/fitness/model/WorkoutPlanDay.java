package com.project.fitness.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/** One day entry inside a WorkoutPlan */
@Entity
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class WorkoutPlanDay {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_plan_day_plan"))
    @JsonIgnore
    private WorkoutPlan plan;

    private Integer dayNumber;             // 1, 2, 3 …

    @Enumerated(EnumType.STRING)
    private ActivityType activityType;     // REST days use null

    private Integer durationMinutes;
    private String instructions;           // e.g. "3 sets of 10 push-ups, rest 60s"
    private Boolean isRestDay;
}
