package com.project.fitness.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Tracks weight / height / BMI history over time.
 * Every time user updates their weight, a new row is inserted here.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BodyMetricsLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_body_metrics_user"))
    @JsonIgnore
    private User user;

    private Double weight;   // kg
    private Double height;   // cm
    private Double bmi;      // calculated: weight / (height_m)^2

    private String bmiCategory;  // Underweight / Normal / Overweight / Obese

    @CreationTimestamp
    private LocalDateTime loggedAt;
}
