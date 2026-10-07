package com.project.fitness.repository;

import com.project.fitness.model.Notification;
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
public interface NotificationRepository extends JpaRepository<Notification, String> {

    Page<Notification> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    /**
     * Counted in the database. The badge polls this, so it must not grow with the
     * user's history the way loading every row and filtering in Java would (AGENT.md §9).
     */
    long countByUserIdAndReadAtIsNull(String userId);

    /** The dedupe check — see Notification.dedupeKey. */
    boolean existsByUserIdAndDedupeKey(String userId, String dedupeKey);

    /**
     * Marks everything unread as read in one statement.
     *
     * <p>Loading a hundred rows to set one column on each is the pattern §9 exists to
     * stop, and this is the one action guaranteed to touch every unread row at once.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.user.id = :userId AND n.readAt IS NULL")
    int markAllRead(@Param("userId") String userId, @Param("now") LocalDateTime now);

    /** Housekeeping: read notifications older than a cutoff. */
    @Modifying
    @Query("DELETE FROM Notification n WHERE n.readAt IS NOT NULL AND n.createdAt < :cutoff")
    int deleteReadOlderThan(@Param("cutoff") LocalDateTime cutoff);

    List<Notification> findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(String userId);
}
