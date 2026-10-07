package com.project.fitness.repository;

import com.project.fitness.model.BodyMetricsLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BodyMetricsLogRepository extends JpaRepository<BodyMetricsLog, String> {

    List<BodyMetricsLog> findByUserIdOrderByLoggedAtAsc(String userId);

    /** Weight history, one page at a time — also bounds the trend embedded in /metrics (PLAN P3-3). */
    Page<BodyMetricsLog> findByUserId(String userId, Pageable pageable);

    Optional<BodyMetricsLog> findTopByUserIdOrderByLoggedAtDesc(String userId);
}
