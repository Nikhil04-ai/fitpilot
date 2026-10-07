package com.project.fitness.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class SleepLog {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_sleep_log_user"))
    @JsonIgnore
    private User user;

    private LocalDate sleepDate;        // the date the person went to sleep
    private Double hoursSlept;          // e.g. 7.5
    private Integer qualityRating;      // 1–5

    // Optional: bedtime / wake time for future analysis
    private LocalDateTime bedtime;
    private LocalDateTime wakeTime;

    private String notes;

    @CreationTimestamp
    private LocalDateTime loggedAt;
}
