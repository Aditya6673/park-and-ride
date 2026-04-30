package com.parkride.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Kafka event published by the Payment Service for every wallet transaction.
 *
 * <p>Topic: {@code payment-events}
 * <p>Consumed by: Notification Service, Analytics Service, Audit Service.
 *
 * <p><b>Idempotency:</b> Every payment operation carries an {@link #idempotencyKey}.
 * Consumers must track processed keys and skip duplicates. The Payment Service
 * guarantees exactly-once writes via the unique constraint on {@code idempotency_key}
 * in the transactions table.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentEvent {

    // ── Event metadata ────────────────────────────────────────────────────────

    /** Unique event ID for consumer-side deduplication. */
    @JsonProperty("eventId")
    private UUID eventId;

    /** Type of payment transaction. */
    @JsonProperty("eventType")
    private EventType eventType;

    /** Time at which the Payment Service emitted this event. */
    @JsonProperty("occurredAt")
    private Instant occurredAt;

    // ── Transaction payload ───────────────────────────────────────────────────

    /** Internal transaction ID in the Payment Service database. */
    @JsonProperty("transactionId")
    private UUID transactionId;

    /**
     * Caller-supplied idempotency key.
     * Consumers use this to avoid processing the same event twice
     * if the Kafka consumer retries after a failure.
     */
    @JsonProperty("idempotencyKey")
    private String idempotencyKey;

    /** The user whose wallet was affected. */
    @JsonProperty("userId")
    private UUID userId;

    /** The wallet that was debited or credited. */
    @JsonProperty("walletId")
    private UUID walletId;

    /**
     * Amount of the transaction (always positive).
     * The {@link #eventType} determines whether this is a debit or credit.
     */
    @JsonProperty("amount")
    private BigDecimal amount;

    /**
     * Wallet balance after this transaction.
     * Useful for Notification Service to include in balance-update emails.
     */
    @JsonProperty("balanceAfter")
    private BigDecimal balanceAfter;

    // ── Reference ─────────────────────────────────────────────────────────────

    /**
     * The entity that triggered this payment — a booking ID or ride ID.
     * Consumers use this to correlate the payment with a booking or ride record.
     */
    @JsonProperty("referenceId")
    private UUID referenceId;

    /**
     * Type of the {@link #referenceId}: {@code BOOKING} or {@code RIDE}.
     * Allows consumers to look up the correct entity without ambiguity.
     */
    @JsonProperty("referenceType")
    private String referenceType;

    /**
     * External payment gateway transaction ID (Razorpay / Stripe order ID).
     * Null for wallet-funded transactions where no gateway is involved.
     */
    @JsonProperty("gatewayTransactionId")
    private String gatewayTransactionId;

    /**
     * Terminal status of the transaction.
     * Consumers should only act on {@code SUCCESS} events; ignore {@code PENDING}.
     */
    @JsonProperty("status")
    private String status;

    /**
     * Human-readable failure reason.
     * Populated only when {@link #status} is {@code FAILED}.
     * Used by Notification Service to inform the user of the failure reason.
     */
    @JsonProperty("failureReason")
    private String failureReason;

    /**
     * Loyalty points earned or redeemed in this transaction.
     * Null when no loyalty points were involved.
     */
    @JsonProperty("loyaltyPointsDelta")
    private Integer loyaltyPointsDelta;

    // ── Event type discriminator ──────────────────────────────────────────────

    public enum EventType {

        /** Wallet topped up by the user via payment gateway. */
        WALLET_TOPUP,

        /** Wallet debited for a parking booking or ride payment. */
        PAYMENT_CHARGED,

        /** Refund credited back to the user's wallet after booking cancellation. */
        REFUND_PROCESSED,

        /** Payment attempt failed — triggers retry via Kafka DLQ mechanism. */
        PAYMENT_FAILED,

        /** Loyalty points redeemed as partial or full payment. */
        LOYALTY_REDEEMED,

        /** Loyalty points earned after a completed booking or ride. */
        LOYALTY_EARNED,

        /** Metro card balance deducted via third-party integration. */
        METRO_CARD_CHARGED
    }
}
