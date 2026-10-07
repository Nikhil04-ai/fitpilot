package com.project.fitness.repository;

import com.project.fitness.model.Activity;
import com.project.fitness.model.ActivityType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ActivityRepository extends JpaRepository<Activity, String> {

    // Paginated — used by GET /api/activities
    Page<Activity> findByUserId(String userId, Pageable pageable);

    // Non-paginated — used internally by services (stats, plateau, etc.)
    List<Activity> findByUserId(String userId);

    List<Activity> findByUserIdAndStartTimeBetween(String userId, LocalDateTime from, LocalDateTime to);

    @Query("SELECT COALESCE(SUM(a.caloriesBurned), 0) FROM Activity a " +
           "WHERE a.user.id = :userId AND a.startTime BETWEEN :from AND :to")
    Integer sumCaloriesByUserIdAndDateRange(@Param("userId") String userId,
                                            @Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to);

    @Query("SELECT COALESCE(SUM(a.duration), 0) FROM Activity a " +
           "WHERE a.user.id = :userId AND a.startTime BETWEEN :from AND :to")
    Integer sumDurationByUserIdAndDateRange(@Param("userId") String userId,
                                             @Param("from") LocalDateTime from,
                                             @Param("to") LocalDateTime to);

    List<Activity> findTop3ByUserIdAndTypeOrderByStartTimeDesc(String userId, ActivityType type);

    /**
     * Counted in the database rather than by loading every row and calling .size().
     * This runs on every single activity save (RecommendationService decides whether
     * this is the 3rd/6th/9th activity), so the old version's cost grew with the
     * user's entire history on every write.
     */
    long countByUserId(String userId);

    /** The N most recent activities — replaces "load everything, sort in memory, take 5". */
    List<Activity> findTop5ByUserIdOrderByStartTimeDesc(String userId);

    /** All-time totals, aggregated in the database instead of in the JVM. */
    @Query("SELECT COALESCE(SUM(a.caloriesBurned), 0) FROM Activity a WHERE a.user.id = :userId")
    Integer sumCaloriesByUserId(@Param("userId") String userId);

    @Query("SELECT COALESCE(SUM(a.duration), 0) FROM Activity a WHERE a.user.id = :userId")
    Integer sumDurationByUserId(@Param("userId") String userId);

    /**
     * Ids of users who trained since a given moment.
     *
     * <p>For the scheduled weekly digest (PLAN P2-3), which costs one Gemini call per
     * user it runs for. Generating for everyone who ever registered would spend the
     * shared quota on dormant accounts that will never open the result.
     */
    @Query("SELECT DISTINCT a.user.id FROM Activity a WHERE a.startTime >= :since")
    List<String> findUserIdsWithActivitySince(@Param("since") LocalDateTime since);
}
