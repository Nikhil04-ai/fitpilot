package com.project.fitness.repository;

import com.project.fitness.model.BodyPart;
import com.project.fitness.model.PainLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PainLogRepository extends JpaRepository<PainLog, String> {

    List<PainLog> findByUserId(String userId);

    /** The pain history panel, one page at a time (PLAN P3-3). */
    Page<PainLog> findByUserId(String userId, Pageable pageable);

    List<PainLog> findByUserIdAndBodyPartAndLoggedAtAfter(
            String userId, BodyPart bodyPart, LocalDateTime after);

    /**
     * Detaches every pain log from an activity that is about to be deleted.
     *
     * <p>The foreign key has no ON DELETE rule, so a linked activity could not be
     * deleted at all (PLAN P4-6). The pain is still real after the workout entry is
     * gone, so the logs stay and only lose the link.
     */
    @Modifying
    @Query("UPDATE PainLog p SET p.activity = null WHERE p.activity.id = :activityId")
    int unlinkActivity(@Param("activityId") String activityId);
}
