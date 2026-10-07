package com.project.fitness.repository;

import com.project.fitness.model.WeeklyDigest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface WeeklyDigestRepository extends JpaRepository<WeeklyDigest, String> {

    /** The digest for one week, if it has been generated. Backed by a unique constraint. */
    Optional<WeeklyDigest> findByUserIdAndWeekStart(String userId, LocalDate weekStart);

    /** History, newest week first. */
    List<WeeklyDigest> findByUserIdOrderByWeekStartDesc(String userId);

    Optional<WeeklyDigest> findTopByUserIdOrderByWeekStartDesc(String userId);
}
