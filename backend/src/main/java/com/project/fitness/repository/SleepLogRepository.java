package com.project.fitness.repository;

import com.project.fitness.model.SleepLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SleepLogRepository extends JpaRepository<SleepLog, String> {
    List<SleepLog> findByUserIdOrderBySleepDateDesc(String userId);
    /** Sleep history, one page at a time (PLAN P3-3). */
    Page<SleepLog> findByUserId(String userId, Pageable pageable);
    List<SleepLog> findByUserIdAndSleepDateBetweenOrderBySleepDateAsc(String userId, LocalDate from, LocalDate to);
    Optional<SleepLog> findByUserIdAndSleepDate(String userId, LocalDate date);

    @Query("SELECT AVG(s.hoursSlept) FROM SleepLog s WHERE s.user.id = :userId AND s.sleepDate BETWEEN :from AND :to")
    Double avgHoursByUserIdAndDateRange(@Param("userId") String userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT AVG(s.qualityRating) FROM SleepLog s WHERE s.user.id = :userId AND s.sleepDate BETWEEN :from AND :to")
    Double avgQualityByUserIdAndDateRange(@Param("userId") String userId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
