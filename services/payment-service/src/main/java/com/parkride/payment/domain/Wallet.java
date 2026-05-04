package com.parkride.payment.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A user's wallet — one wallet per user (enforced by UNIQUE constraint on user_id).
 *
 * <p>{@code version} enables JPA optimistic locking: concurrent debit/credit
 * operations will raise {@link jakarta.persistence.OptimisticLockException}
 * rather than silently overwriting each other's balance updates.
 */
@Entity
@Table(
    name = "wallets",
    indexes = @Index(name = "idx_wallets_user_id", columnList = "user_id", unique = true)
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** The user this wallet belongs to. One wallet per user. */
    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    /**
     * Current balance in INR.
     * Never goes below zero — {@code WalletService} enforces this invariant.
     */
    @Column(name = "balance", nullable = false, precision = 12, scale = 2)
    private BigDecimal balance;

    /** Accumulated loyalty points (1 point ≈ ₹0.10 in redemption). */
    @Column(name = "loyalty_points", nullable = false)
    private Integer loyaltyPoints;

    /**
     * JPA optimistic lock version.
     * Prevents lost updates when two requests debit the same wallet simultaneously.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
