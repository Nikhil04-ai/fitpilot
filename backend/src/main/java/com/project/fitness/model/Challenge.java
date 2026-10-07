package com.project.fitness.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Feature 14 — Workout Buddy / Challenge System
 *
 * <p>Any number of people compete over a fixed time window (default 7 days). A
 * scheduled job updates scores nightly and declares a winner at the end date.
 *
 * <p><b>This was hard-wired to exactly two people</b> until PLAN P2-4: an
 * {@code opponent} field beside the creator, a {@code creatorScore} and an
 * {@code opponentScore} column, and a {@code /h2h/{userAId}/{userBId}} route. Three
 * people were not an unimplemented feature — they were unrepresentable, because the
 * shape of the table said two.
 *
 * <p>{@link ChallengeParticipant} now holds who is in and what they scored, and those
 * two score columns are gone rather than kept alongside it. Two places holding the same
 * number is two places that can disagree, and the parent row's copy would have been the
 * stale one every time.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Challenge {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    // --- Participants ---
    /** Who set it up. Still meaningful: only they may cancel it before it starts. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_challenge_creator"))
    @JsonIgnore
    private User creator;

    /**
     * Everyone in it, creator included.
     *
     * <p>Cascaded and orphan-removed, so deleting a challenge takes its participants
     * with it rather than leaving rows pointing at nothing.
     */
    @OneToMany(mappedBy = "challenge", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @JsonIgnore
    private List<ChallengeParticipant> participants = new ArrayList<>();

    // --- Challenge definition ---
    private String title;

    @Enumerated(EnumType.STRING)
    private ChallengeType type;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ChallengeStatus status = ChallengeStatus.PENDING;

    // --- Time window ---
    private LocalDate startDate;
    private LocalDate endDate;

    // --- Outcome ---
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "winner_id", nullable = true,
                foreignKey = @ForeignKey(name = "fk_challenge_winner"))
    @JsonIgnore
    private User winner;   // null until COMPLETED; null means draw

    private String resultMessage;   // human-readable result summary

    // --- Leaderboard history entries for this challenge ---
    @OneToMany(mappedBy = "challenge", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @JsonIgnore
    private List<ChallengeLeaderboardEntry> leaderboardHistory = new ArrayList<>();

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** Adds someone to the challenge, keeping both sides of the relationship in step. */
    public void addParticipant(ChallengeParticipant participant) {
        participant.setChallenge(this);
        participants.add(participant);
    }

    /** Only those who accepted are ranked; an unanswered invite is not a zero score. */
    public List<ChallengeParticipant> competitors() {
        return participants.stream().filter(ChallengeParticipant::isCompeting).toList();
    }

    public java.util.Optional<ChallengeParticipant> participantFor(String userId) {
        return participants.stream()
                .filter(p -> p.getUser() != null && p.getUser().getId().equals(userId))
                .findFirst();
    }

    public boolean involves(String userId) {
        return participantFor(userId).isPresent();
    }
}
