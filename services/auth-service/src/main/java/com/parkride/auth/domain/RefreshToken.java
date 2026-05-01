package com.parkride.auth.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent refresh token record.
 *
 * <p>The actual JWT refresh token string is never stored — only its SHA-256 hash.
 * This means a database breach exposes no usable tokens.
 *
 * <p>Token rotation: every call to {@code POST /auth/refresh} invalidates the
 * presented token (sets {@code revoked = true}) and issues a brand-new token.
 * This limits the blast radius of a stolen refresh token to at most one use.
 *
 * <p>Cleanup: expired and revoked tokens are purged by a scheduled job in
 * {@code TokenService} to prevent unbounded table growth.
 */
@Entity
@Table(
    name = "refresh_tokens",
    indexes = {
        @Index(name = "idx_refresh_tokens_user_id",   columnList = "user_id"),
        @Index(name = "idx_refresh_tokens_token_hash", columnList = "token_hash")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** The user this token belongs to. Stored as a plain UUID FK, not a JPA relation. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * SHA-256 hex digest of the raw refresh JWT.
     * Lookup: hash the incoming token, query by hash, compare.
     */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Set to {@code true} immediately when the token is rotated or revoked on logout. */
    @Column(name = "is_revoked", nullable = false)
    @Builder.Default
    private boolean revoked = false;

    /** Device or client identifier — informational, for session management UI. */
    @Column(name = "device_info", length = 255)
    private String deviceInfo;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    // ── Domain helpers ────────────────────────────────────────────────────

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !revoked && !isExpired();
    }

    public void revoke() {
        this.revoked = true;
    }
}
