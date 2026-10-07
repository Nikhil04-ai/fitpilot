package com.project.fitness.repository;

import com.project.fitness.model.ChallengeParticipant;
import com.project.fitness.model.ParticipantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChallengeParticipantRepository extends JpaRepository<ChallengeParticipant, String> {

    Optional<ChallengeParticipant> findByChallengeIdAndUserId(String challengeId, String userId);

    List<ChallengeParticipant> findByChallengeIdOrderByScoreDesc(String challengeId);

    /** Invitations waiting on this user to answer — the badge on the Challenges link. */
    @Query("""
           SELECT p FROM ChallengeParticipant p
           WHERE p.user.id = :userId AND p.status = :status
           ORDER BY p.invitedAt DESC
           """)
    List<ChallengeParticipant> findByUserIdAndStatus(@Param("userId") String userId,
                                                     @Param("status") ParticipantStatus status);
}
