package com.project.fitness.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * One week, reasoned over as a whole (PLAN P2-3).
 *
 * <p>Gemini was called from exactly two places, and the coaching call was fed almost
 * nothing: age, weight, the last five activities and a streak number. Meanwhile the app
 * held sleep logs and a computed sleep-vs-performance correlation, pain entries by body
 * part, muscle recovery state, hour-of-day timing analysis, body-metrics history,
 * per-exercise loads and 1RM trends, and — since P2-1 — everything eaten. None of it
 * reached the model. This is the record of a call that sees all of it at once, which is
 * where cross-signal statements like "your bench has been flat three weeks and you
 * averaged 5h20 of sleep — those are probably the same fact" become possible at all.
 *
 * <p><b>The facts are computed, not generated.</b> {@link #dataPoints} holds the figures
 * that were fed in, and they are derived from the rows every time. The model is asked to
 * interpret them, never to supply them — so a digest can be re-read months later against
 * what it was actually reasoning about, and a hallucinated number has nowhere to hide.
 * {@link #aiGenerated} records whether the narrative came from the model or from the
 * rule-based fallback, which runs whenever no API key is configured.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(uniqueConstraints = @UniqueConstraint(
        name = "uk_weekly_digest_user_week", columnNames = {"user_id", "week_start"}))
public class WeeklyDigest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_weekly_digest_user"))
    @JsonIgnore
    private User user;

    /**
     * Monday of the week being summarised.
     *
     * <p>Unique per user, so regenerating replaces rather than accumulating a pile of
     * near-identical digests for the same seven days.
     */
    private LocalDate weekStart;

    /** One line, the thing to take away. */
    private String headline;

    @Column(length = 2000)
    private String narrative;

    /** What the week's numbers actually showed. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private List<String> observations;

    /** What to do about it next week. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private List<String> focusNextWeek;

    /**
     * The figures the narrative was written from.
     *
     * <p>Stored so the digest stays checkable. Without it, a sentence about a 12% drop
     * in volume is unverifiable the moment the week scrolls out of view.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private Map<String, Object> dataPoints;

    /** False when the rule-based fallback wrote this, which the UI says plainly. */
    @Builder.Default
    private Boolean aiGenerated = false;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
