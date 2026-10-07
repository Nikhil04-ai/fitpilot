package com.project.fitness.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A connection between two users (PLAN P2-4).
 *
 * <p>Challenges existed before friends did, which meant the only way to challenge
 * somebody was to know their raw UUID and paste it in — the Profile panel literally has
 * a "Your Athlete ID — share to receive challenges" box for exactly that reason. That
 * is not a feature, it is the absence of one.
 *
 * <p><b>One row per pair, not two.</b> A friendship is symmetric, so storing it from
 * both sides would mean two rows that have to agree about a single fact — and the day
 * they disagree, one person has a friend who does not have them. The direction is kept
 * only to know who asked, which is what the addressee needs in order to answer.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(
    uniqueConstraints = @UniqueConstraint(
        name = "uk_friendship_pair", columnNames = {"requester_id", "addressee_id"}),
    indexes = {
        @Index(name = "idx_friendship_requester", columnList = "requester_id, status"),
        @Index(name = "idx_friendship_addressee", columnList = "addressee_id, status")
    })
public class Friendship {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Who sent the request. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_friendship_requester"))
    @JsonIgnore
    private User requester;

    /** Who was asked, and who alone can accept. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "addressee_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_friendship_addressee"))
    @JsonIgnore
    private User addressee;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private FriendshipStatus status = FriendshipStatus.PENDING;

    @CreationTimestamp
    private LocalDateTime createdAt;

    /** When it was accepted. Null while pending. */
    private LocalDateTime respondedAt;

    /** The other party, given one side of it. */
    public User otherThan(String userId) {
        if (requester != null && requester.getId().equals(userId)) return addressee;
        return requester;
    }

    public boolean involves(String userId) {
        return (requester != null && requester.getId().equals(userId))
                || (addressee != null && addressee.getId().equals(userId));
    }
}
