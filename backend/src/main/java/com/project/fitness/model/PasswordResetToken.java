package com.project.fitness.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A single-use, short-lived password reset grant (PLAN P1-4).
 *
 * <p>Only a SHA-256 hash of the token is stored. The raw value exists in exactly two
 * places — the email that was sent, and the link the person clicks — and never on
 * disk here. A reset token is a password equivalent for as long as it lives, so a
 * leaked database backup must not hand over working ones.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "password_reset_token",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_password_reset_token_hash",
               columnNames = "token_hash"))
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_password_reset_user"))
    @JsonIgnore
    private User user;

    /** SHA-256 of the raw token, hex-encoded. Unique so a lookup is a direct hit. */
    @Column(nullable = false)
    private String tokenHash;

    private LocalDateTime expiresAt;

    /** Set the moment the token is spent, so a link cannot be replayed. */
    private LocalDateTime usedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public boolean isExpired() {
        return expiresAt == null || LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isUsable() {
        return !isUsed() && !isExpired();
    }
}
