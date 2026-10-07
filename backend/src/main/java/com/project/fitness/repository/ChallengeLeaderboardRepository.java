package com.project.fitness.repository;

import com.project.fitness.model.ChallengeLeaderboardEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChallengeLeaderboardRepository extends JpaRepository<ChallengeLeaderboardEntry, String> {

    List<ChallengeLeaderboardEntry> findByChallengeIdOrderBySnapshotDateAsc(String challengeId);
}
