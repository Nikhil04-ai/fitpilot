package com.project.fitness.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Phase 4 — Refresh Token System
 * accessToken  → 48 hours
 * refreshToken → 30 days
 *
 * <p>Only the SHA-256 of the token is stored (PLAN P5-13). The raw value goes to the
 * client once, when the token is issued, and is hashed again to be looked up. It used to
 * be stored as issued, so a read of this table yielded 30-day sessions for every account.
 */
@Entity
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_refresh_token_user"))
    @JsonIgnore
    private User user;

    @Column(name = "token_hash", unique = true, nullable = false)
    private String tokenHash;   // TokenHash.of(the UUID the client holds and sends to /api/auth/refresh)

    private LocalDateTime expiresAt;   // 30 days from creation

    @Builder.Default
    private Boolean revoked = false;   // true after logout or token rotation

    @CreationTimestamp
    private LocalDateTime createdAt;

    public static String generate() {
        return UUID.randomUUID().toString();
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
}
