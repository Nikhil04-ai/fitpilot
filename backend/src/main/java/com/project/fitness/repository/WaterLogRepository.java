package com.project.fitness.repository;

import com.project.fitness.model.WaterLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface WaterLogRepository extends JpaRepository<WaterLog, String> {
    List<WaterLog> findByUserIdAndLogDate(String userId, LocalDate date);

    /** Whole history, oldest first — the data export (PLAN P2-5). */
    List<WaterLog> findByUserIdOrderByLogDateAsc(String userId);
    List<WaterLog> findByUserIdAndLogDateBetweenOrderByLogDateAsc(String userId, LocalDate from, LocalDate to);

    @Query("SELECT COALESCE(SUM(w.amountMl), 0) FROM WaterLog w WHERE w.user.id = :userId AND w.logDate = :date")
    Double sumByUserIdAndDate(@Param("userId") String userId, @Param("date") LocalDate date);
}
