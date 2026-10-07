package com.project.fitness.repository;

import com.project.fitness.model.Exercise;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExerciseRepository extends JpaRepository<Exercise, String> {

    /**
     * Every set of one movement for one user, newest workout first.
     *
     * <p>Joined through the activity rather than denormalising a user id onto the
     * exercise row — an exercise has no meaning apart from the workout it belongs to,
     * and two columns that must agree about ownership are two columns that can disagree.
     */
    @Query("""
           SELECT e FROM Exercise e
           WHERE e.activity.user.id = :userId AND e.normalisedName = :normalisedName
           ORDER BY e.activity.startTime DESC
           """)
    List<Exercise> findForUserByName(@Param("userId") String userId,
                                     @Param("normalisedName") String normalisedName);

    /** Every exercise a user has ever logged — used to rebuild records after an edit. */
    @Query("SELECT e FROM Exercise e WHERE e.activity.user.id = :userId")
    List<Exercise> findAllForUser(@Param("userId") String userId);

    /**
     * The same, with the parent workout fetched.
     *
     * <p>For the data export (PLAN P2-5), which writes each movement's workout date
     * alongside it. Without the join fetch that getter is a lazy load per row, so
     * exporting a year of lifting would fire one query per set (AGENT.md §9).
     */
    @Query("""
           SELECT e FROM Exercise e
           JOIN FETCH e.activity a
           WHERE a.user.id = :userId
           ORDER BY a.startTime ASC, e.orderIndex ASC
           """)
    List<Exercise> findAllForUserWithActivity(@Param("userId") String userId);
}
