package com.parkride.auth.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Core user aggregate root.
 *
 * <p>Passwords are never stored in plain text — only BCrypt hashes (cost factor 12).
 * The actual hashing is performed in {@code AuthService} before saving.
 *
 * <p>Account locking: after {@code MAX_FAILED_ATTEMPTS} consecutive failed logins,
 * {@code lockedUntil} is set to {@code now + 15 minutes}. The Security config
 * delegates lockout checking to {@code UserDetailsServiceImpl}.
 */
@Entity
@Table(
    name = "users",
    uniqueConstraints = @UniqueConstraint(name = "uk_users_email", columnNames = "email")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    public static final int MAX_FAILED_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "phone", length = 20)
    private String phone;

    /** Whether the user has verified their email address. */
    @Column(name = "is_verified", nullable = false)
    @Builder.Default
    private boolean verified = false;

    /** Soft-disable without deleting the account. */
    @Column(name = "is_enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    /** Incremented on each failed login; reset to 0 on successful login. */
    @Column(name = "failed_login_attempts", nullable = false)
    @Builder.Default
    private int failedLoginAttempts = 0;

    /**
     * Non-null when the account is temporarily locked.
     * Lock expires automatically when this instant passes.
     */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    /**
     * Email verification token stored as a hash.
     * Cleared once the user successfully verifies their address.
     */
    @Column(name = "verification_token_hash")
    private String verificationTokenHash;

    /** Expiry for the email verification token. */
    @Column(name = "verification_token_expires_at")
    private Instant verificationTokenExpiresAt;

    /** Password reset token hash — set during forgot-password flow. */
    @Column(name = "password_reset_token_hash")
    private String passwordResetTokenHash;

    @Column(name = "password_reset_token_expires_at")
    private Instant passwordResetTokenExpiresAt;

    @ManyToMany(fetch = FetchType.EAGER, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
        name = "user_roles",
        joinColumns        = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    // ── Domain helpers ────────────────────────────────────────────────────

    public boolean isAccountNonLocked() {
        return lockedUntil == null || Instant.now().isAfter(lockedUntil);
    }

    public void incrementFailedAttempts() {
        this.failedLoginAttempts++;
        if (this.failedLoginAttempts >= MAX_FAILED_ATTEMPTS) {
            this.lockedUntil = Instant.now().plusSeconds(15 * 60);
        }
    }

    public void resetFailedAttempts() {
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }

    public void addRole(Role role) {
        this.roles.add(role);
    }
}
