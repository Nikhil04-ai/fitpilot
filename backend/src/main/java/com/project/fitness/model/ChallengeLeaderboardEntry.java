package com.project.fitness.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * One snapshot per day per challenge.
 *
 * <p>The scheduler writes this every night so a score graph over time is possible.
 *
 * <p>The two score columns became {@link #standings} with PLAN P2-4: "creatorScore and
 * opponentScore" cannot describe three people, and adding a third column would only
 * have moved the same wall out by one.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChallengeLeaderboardEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "challenge_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_leaderboard_challenge"))
    @JsonIgnore
    private Challenge challenge;

    private LocalDate snapshotDate;

    /**
     * Everyone's score at this moment: {@code [{userId, name, score}, ...]}, highest
     * first. JSON rather than a row per participant per day, because a snapshot is read
     * and written whole and never queried by participant.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private List<Map<String, Object>> standings;

    // who was leading at this snapshot
    private String leadingUserId;
    private Double leadMargin;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
