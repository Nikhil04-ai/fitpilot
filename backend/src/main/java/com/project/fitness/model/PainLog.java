package com.project.fitness.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Feature 6 — Pain Point Logger
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PainLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_pain_log_user"))
    @JsonIgnore
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_id",
                foreignKey = @ForeignKey(name = "fk_pain_log_activity"))
    @JsonIgnore
    private Activity activity;

    @Enumerated(EnumType.STRING)
    private BodyPart bodyPart;

    private Integer severity;    // 1–5
    private String description;

    @CreationTimestamp
    private LocalDateTime loggedAt;
}
