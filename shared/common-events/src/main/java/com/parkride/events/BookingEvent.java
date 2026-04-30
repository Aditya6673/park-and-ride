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
 * Kafka event published by the Parking Service for every booking lifecycle transition.
 *
 * <p>Topic: {@code booking-events}
 * <p>Consumed by: Notification Service, Payment Service, Pricing Service, Analytics Service.
 *
 * <p>Schema stability: fields may only be added (never removed or renamed) once any
 * consumer is deployed. Additions are backward-compatible; removals break consumers.
 *
 * <p>JSON shape:
 * <pre>
 * {
 *   "eventId":     "550e8400-e29b-41d4-a716-446655440000",
 *   "eventType":   "BOOKING_CONFIRMED",
 *   "bookingId":   "...",
 *   "userId":      "...",
 *   "slotId":      "...",
 *   "lotId":       "...",
 *   "status":      "CONFIRMED",
 *   "amount":      150.00,
 *   "startTime":   "2025-06-01T08:00:00Z",
 *   "endTime":     "2025-06-01T10:00:00Z",
 *   "occurredAt":  "2025-06-01T07:55:00Z"
 * }
 * </pre>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BookingEvent {

    // ── Event metadata ────────────────────────────────────────────────────────

    /**
     * Unique ID for this event instance.
     * Used for idempotency checks in consumers — processing the same eventId
     * twice must produce the same result and not trigger duplicate notifications or charges.
     */
    @JsonProperty("eventId")
    private UUID eventId;

    /**
     * Discriminates the booking transition that triggered this event.
     * Consumers switch on this field to decide which action to take.
     */
    @JsonProperty("eventType")
    private EventType eventType;

    /** Wall-clock time when the event was emitted by the Parking Service. */
    @JsonProperty("occurredAt")
    private Instant occurredAt;

    // ── Booking payload ───────────────────────────────────────────────────────

    /** The booking that changed state. */
    @JsonProperty("bookingId")
    private UUID bookingId;

    /** The user who owns the booking. */
    @JsonProperty("userId")
    private UUID userId;

    /** The specific parking slot that was reserved. */
    @JsonProperty("slotId")
    private UUID slotId;

    /** The parking lot containing the slot — useful for lot-level aggregations. */
    @JsonProperty("lotId")
    private UUID lotId;

    /**
     * The booking status after the transition.
     * Mirrors {@code BookingStatus} in the Parking Service domain.
     */
    @JsonProperty("status")
    private String status;

    /**
     * The amount charged or to be refunded, depending on {@link #eventType}.
     * Null for status transitions that have no financial implication
     * (e.g., CHECKED_IN events).
     */
    @JsonProperty("amount")
    private BigDecimal amount;

    /** Requested parking start time. */
    @JsonProperty("startTime")
    private Instant startTime;

    /** Requested parking end time. */
    @JsonProperty("endTime")
    private Instant endTime;

    /**
     * QR code token — included only in BOOKING_CONFIRMED events.
     * The Notification Service attaches this to the confirmation email.
     * Null for all other event types.
     */
    @JsonProperty("qrCodeToken")
    private String qrCodeToken;

    // ── Event type discriminator ──────────────────────────────────────────────

    /**
     * All booking lifecycle transitions that produce a Kafka event.
     *
     * <p>Consumers should handle unknown values gracefully (forward compatibility):
     * <pre>
     * switch (event.getEventType()) {
     *     case BOOKING_CONFIRMED -> sendConfirmationEmail(event);
     *     case BOOKING_CANCELLED -> triggerRefund(event);
     *     default -> log.debug("Unhandled event type: {}", event.getEventType());
     * }
     * </pre>
     */
    public enum EventType {

        /** Payment confirmed, slot reserved — triggers confirmation email + QR. */
        BOOKING_CONFIRMED,

        /** User or system cancelled the booking — triggers refund if applicable. */
        BOOKING_CANCELLED,

        /** User successfully checked into the parking slot (QR or RFID scan). */
        SLOT_CHECKED_IN,

        /** Parking session completed (user exited). */
        BOOKING_COMPLETED,

        /**
         * Booking auto-cancelled after the no-show grace period (15 minutes).
         * Treated like BOOKING_CANCELLED by Payment; Notification uses a
         * different message template for this case.
         */
        BOOKING_NO_SHOW,

        /** Slot availability changed — consumed by Pricing Service for surge recalculation. */
        SLOT_AVAILABILITY_CHANGED
    }
}
