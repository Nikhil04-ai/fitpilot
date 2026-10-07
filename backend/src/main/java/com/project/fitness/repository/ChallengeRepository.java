package com.project.fitness.repository;

import com.project.fitness.model.Challenge;
import com.project.fitness.model.ChallengeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Every "who is in this challenge" question now goes through the participant table
 * (PLAN P2-4). These queries used to read {@code c.creator.id OR c.opponent.id}, which
 * was not a filter that could be widened — it was the assumption that there were
 * exactly two people, written into every lookup.
 */
@Repository
public interface ChallengeRepository extends JpaRepository<Challenge, String> {

    /** All challenges a user is part of, in any role. */
    @Query("SELECT DISTINCT c FROM Challenge c JOIN c.participants p WHERE p.user.id = :userId")
    List<Challenge> findAllByUserId(@Param("userId") String userId);

    /** Active challenges past their end date — used by the settlement job. */
    @Query("SELECT c FROM Challenge c WHERE c.status = 'ACTIVE' AND c.endDate < :today")
    List<Challenge> findExpiredActiveChallenges(@Param("today") LocalDate today);

    /** All currently ACTIVE challenges — used by the nightly score updater. */
    List<Challenge> findByStatus(ChallengeStatus status);

    /** Challenges with an unanswered invitation for this user. */
    @Query("""
           SELECT DISTINCT c FROM Challenge c JOIN c.participants p
           WHERE p.user.id = :userId AND p.status = 'INVITED'
             AND c.status IN ('PENDING', 'ACTIVE')
           """)
    List<Challenge> findPendingInvitesForUser(@Param("userId") String userId);

    /**
     * Completed challenges both users took part in.
     *
     * <p>Still meaningful with groups — it answers "how have the two of us done when
     * we've both been in something", which is what the head-to-head view shows — but it
     * is a participation test now rather than a creator/opponent equality test.
     */
    @Query("""
           SELECT DISTINCT c FROM Challenge c
           JOIN c.participants pa JOIN c.participants pb
           WHERE c.status = 'COMPLETED'
             AND pa.user.id = :userA AND pb.user.id = :userB
             AND pa.status = 'ACCEPTED' AND pb.status = 'ACCEPTED'
           """)
    List<Challenge> findH2HHistory(@Param("userA") String userA, @Param("userB") String userB);
}
