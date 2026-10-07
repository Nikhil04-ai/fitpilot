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

    @Column(unique = true, nullable = false)
    private String token;       // UUID — stored here, client sends this to /api/auth/refresh

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
