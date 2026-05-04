package com.parkride.payment.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A single debit or credit entry on a wallet.
 *
 * <p><b>Idempotency:</b> {@code idempotency_key} has a UNIQUE database constraint.
 * The key is derived as {@code bookingId + ":" + eventType}. If the same Kafka
 * message is replayed, the duplicate INSERT will throw
 * {@link org.springframework.dao.DataIntegrityViolationException}, which the
 * consumer catches and ignores — safe to retry, no double-charge.
 */
@Entity
@Table(
    name = "transactions",
    indexes = {
        @Index(name = "idx_transactions_wallet_id", columnList = "wallet_id"),
        @Index(name = "idx_transactions_reference_id", columnList = "reference_id"),
        @Index(name = "uq_transactions_idempotency_key", columnList = "idempotency_key", unique = true)
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private TransactionType type;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /**
     * Idempotency key — consumers derive this as {@code referenceId + ":" + eventType}.
     * UNIQUE constraint guarantees exactly-once semantics for Kafka at-least-once delivery.
     */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    /**
     * The booking or ride ID that triggered this transaction.
     * Allows correlation of financial records back to the originating event.
     */
    @Column(name = "reference_id")
    private UUID referenceId;

    /**
     * Discriminates the type of {@code referenceId}: {@code BOOKING} or {@code RIDE}.
     */
    @Column(name = "reference_type", length = 20)
    private String referenceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status;

    /** Human-readable failure reason — populated only when status is FAILED. */
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;
}
