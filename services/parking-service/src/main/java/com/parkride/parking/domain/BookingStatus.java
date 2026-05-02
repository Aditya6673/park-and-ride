package com.parkride.parking.domain;

/**
 * Booking lifecycle states.
 *
 * <p>Valid transitions:
 * <pre>
 * PENDING → CONFIRMED → CHECKED_IN → COMPLETED
 *                    ↘ CANCELLED
 * CONFIRMED → NO_SHOW  (auto-cancelled after 30-min grace period)
 * PENDING   → CANCELLED (user cancels before confirmation)
 * </pre>
 */
public enum BookingStatus {

    /** Slot tentatively reserved; awaiting payment confirmation. */
    PENDING,

    /** Payment confirmed, slot fully reserved, QR code issued. */
    CONFIRMED,

    /** User scanned QR at gate — currently parked. */
    CHECKED_IN,

    /** Parking session ended; slot released. */
    COMPLETED,

    /** Booking cancelled by user or system before check-in. */
    CANCELLED,

    /** User did not check in within 30 minutes of start time. */
    NO_SHOW
}
