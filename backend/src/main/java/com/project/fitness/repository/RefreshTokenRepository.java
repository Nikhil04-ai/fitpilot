package com.project.fitness.repository;

import com.project.fitness.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

    Optional<RefreshToken> findByToken(String token);

    @Modifying
    @Transactional
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.user.id = :userId")
    void revokeAllByUserId(@Param("userId") String userId);

    /**
     * Revokes one token if it is still live, and says whether this call did it.
     *
     * <p>A conditional UPDATE rather than read-check-write, so two refreshes racing on
     * the same token can't both see it live and both be issued a new pair.
     */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.id = :id AND r.revoked = false")
    int revokeIfActive(@Param("id") String id);

    /** Deletes tokens that can never be used again. Returns how many went. */
    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.revoked = true OR r.expiresAt < :now")
    int deleteSpent(@Param("now") LocalDateTime now);
}
