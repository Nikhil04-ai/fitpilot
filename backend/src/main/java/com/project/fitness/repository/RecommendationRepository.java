package com.project.fitness.repository;

import com.project.fitness.model.Recommendation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecommendationRepository extends JpaRepository<Recommendation, String> {
    List<Recommendation> findByUserId(String userId);

    /** The AI Coach list, one page at a time. Unbounded, it grew with every insight (PLAN P4-19). */
    Page<Recommendation> findByUserId(String userId, Pageable pageable);

    List<Recommendation> findByActivityId(String activityId);
}
