package com.project.fitness.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One person in a challenge (PLAN P2-4).
 *
 * <p>{@link Challenge} was hard-wired to exactly two people: a {@code creator} and an
 * {@code opponent} field, a {@code creatorScore} and an {@code opponentScore} column,
 * and a {@code /h2h/{userAId}/{userBId}} route. Three people could not be represented
 * at all — not because of a missing feature, but because the shape of the table said
 * two.
 *
 * <p><b>This is now the single source of truth for who is in and what they scored.</b>
 * The two score columns are gone rather than kept alongside it: two places holding the
 * same number is two places that can disagree, and the one on the parent row would have
 * been the stale one every time.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(
    uniqueConstraints = @UniqueConstraint(
        name = "uk_challenge_participant", columnNames = {"challenge_id", "user_id"}),
    indexes = @Index(name = "idx_challenge_participant_user", columnList = "user_id"))
public class ChallengeParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "challenge_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_challenge_participant_challenge"))
    @JsonIgnore
    private Challenge challenge;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_challenge_participant_user"))
    @JsonIgnore
    private User user;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ParticipantStatus status = ParticipantStatus.INVITED;

    @Builder.Default
    private Double score = 0.0;

    /**
     * True for the person who created the challenge.
     *
     * <p>Kept here as well as on the parent so the standings can mark them without a
     * second lookup, and so cancelling can be checked against the participant row the
     * caller is already holding.
     */
    @Builder.Default
    private Boolean isCreator = false;

    @CreationTimestamp
    private LocalDateTime invitedAt;

    /** When they accepted or declined. Null while invited. */
    private LocalDateTime respondedAt;

    /** In the running: accepted, and therefore scored and ranked. */
    public boolean isCompeting() {
        return status == ParticipantStatus.ACCEPTED;
    }
}
