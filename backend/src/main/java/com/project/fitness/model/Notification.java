package com.project.fitness.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Something the app worked out that the user has not been told (PLAN P2-2).
 *
 * <p>Three scheduled jobs produced insight that was only ever visible if the user
 * happened to open the right panel on the right day — and one of them, the Monday
 * plateau scan, produced nothing at all: it was a log line and a TODO. Work that nobody
 * is told about is work that may as well not have run.
 *
 * <p>{@link #dedupeKey} is what stops a daily job from re-announcing the same thing
 * every morning. A goal whose deadline is in five days is still in four days tomorrow,
 * and a feed that says so once a day until the deadline is a feed people turn off.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(indexes = {
        // Every read is "this user, unread first, newest first", and the unread badge
        // counts on the same pair.
        @Index(name = "idx_notification_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_notification_dedupe", columnList = "user_id, dedupe_key")
})
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_notification_user"))
    @JsonIgnore
    private User user;

    @Enumerated(EnumType.STRING)
    private NotificationType type;

    private String title;

    @Column(length = 1000)
    private String body;

    /**
     * Which panel clicking this should open — "ai", "goals", "challenges".
     *
     * <p>A panel name rather than a URL, because the frontend already routes on
     * {@code #panel} and owns that mapping. A stored URL would be a second copy of the
     * routing table, in the database, going stale.
     */
    private String targetPanel;

    /**
     * Identifies the thing being announced, so it is only announced once.
     *
     * <p>For example {@code goal-deadline:<goalId>:2026-10-01} — the same goal and the
     * same deadline produce the same key however many times the job runs.
     */
    private String dedupeKey;

    /** Null until read. A timestamp rather than a flag, so "when" survives. */
    private LocalDateTime readAt;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
