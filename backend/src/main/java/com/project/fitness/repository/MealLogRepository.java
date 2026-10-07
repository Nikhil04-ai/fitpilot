package com.project.fitness.repository;

import com.project.fitness.model.MealLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MealLogRepository extends JpaRepository<MealLog, String> {

    /** Paginated history — the Nutrition panel's list. */
    Page<MealLog> findByUserId(String userId, Pageable pageable);

    /**
     * Every meal in a window, oldest first. Backs both the daily breakdown and the
     * net-calorie series, which is why it sorts rather than leaving order undefined:
     * a day reads back in the order it was eaten.
     */
    List<MealLog> findByUserIdAndLoggedAtBetweenOrderByLoggedAtAsc(
            String userId, LocalDateTime from, LocalDateTime to);

    /**
     * Totals for one window, summed in the database.
     *
     * <p>Goal progress calls this on every meal save, so it must not grow with the
     * user's history (AGENT.md §9) — loading a day's rows to add up four columns in
     * Java is exactly the pattern that made logging a workout slower every month.
     */
    @Query("SELECT COALESCE(SUM(m.calories), 0) FROM MealLog m " +
           "WHERE m.user.id = :userId AND m.loggedAt BETWEEN :from AND :to")
    Integer sumCaloriesByUserIdAndRange(@Param("userId") String userId,
                                        @Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to);

    @Query("SELECT COALESCE(SUM(m.proteinGrams), 0) FROM MealLog m " +
           "WHERE m.user.id = :userId AND m.loggedAt BETWEEN :from AND :to")
    Double sumProteinByUserIdAndRange(@Param("userId") String userId,
                                      @Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to);

    long countByUserId(String userId);

    /** Ids of users who logged a meal since a given moment — see the activity twin. */
    @Query("SELECT DISTINCT m.user.id FROM MealLog m WHERE m.loggedAt >= :since")
    List<String> findUserIdsWithMealsSince(@Param("since") LocalDateTime since);
}
