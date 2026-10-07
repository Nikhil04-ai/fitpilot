package com.project.fitness.repository;

import com.project.fitness.model.Goal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface GoalRepository extends JpaRepository<Goal, String> {

    List<Goal> findByUserId(String userId);

    List<Goal> findByUserIdAndIsCompletedFalse(String userId);

    /**
     * Unmet goals whose deadline falls in a window, across all users.
     *
     * <p>For the daily deadline warning (PLAN P2-2). Scanning findAll() and filtering in
     * Java would load every goal in the database each morning to find the handful that
     * are actually due — exactly the shape AGENT.md §9 exists to stop.
     */
    @Query("SELECT g FROM Goal g WHERE g.isCompleted = false " +
           "AND g.deadline IS NOT NULL AND g.deadline BETWEEN :from AND :to")
    List<Goal> findDueBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
