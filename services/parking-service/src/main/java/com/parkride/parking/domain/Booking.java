package com.parkride.parking.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A parking reservation made by a user for a specific slot and time window.
 *
 * <p>The {@link #version} field enables optimistic locking — concurrent updates
 * (e.g. simultaneous cancel + check-in) will fail with {@code ObjectOptimisticLockingFailureException}
 * rather than silently overwriting each other.
 *
 * <p>The {@link #qrToken} is a signed JWT containing {@code bookingId}, {@code slotId},
 * {@code userId}, and expiry. It is validated offline at the gate without a DB call.
 *
 * <p>Cross-service identity: {@link #userId} is a UUID from the Auth Service.
 * There is no cross-service foreign key — ownership is validated by matching
 * the JWT principal against this field.
 */
@Entity
@Table(name = "bookings", indexes = {
        @Index(name = "idx_bookings_user",     columnList = "user_id"),
        @Index(name = "idx_bookings_slot",     columnList = "slot_id"),
        @Index(name = "idx_bookings_status",   columnList = "status"),
        @Index(name = "idx_bookings_qr_token", columnList = "qr_token")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * The user who made this booking.
     * UUID sourced from the Auth Service JWT — no cross-service FK.
     */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false)
    private ParkingSlot slot;

    /** Requested parking start time (inclusive). */
    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    /** Requested parking end time (exclusive). */
    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private BookingStatus status = BookingStatus.PENDING;

    /** Total amount charged for the booking duration. */
    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    /**
     * Signed JWT for gate QR scan.
     * Claims: {@code bookingId}, {@code slotId}, {@code userId}, expiry = endTime + 1h grace.
     * Validated offline by the gate scanner.
     */
    @Column(name = "qr_token", length = 1024)
    private String qrToken;

    /** Actual time the user scanned in at the gate. Null until CHECKED_IN. */
    @Column(name = "check_in_time")
    private Instant checkInTime;

    /** Actual time the user exited. Null until COMPLETED. */
    @Column(name = "check_out_time")
    private Instant checkOutTime;

    /** Populated when status is CANCELLED or NO_SHOW. */
    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    /**
     * Optimistic lock version.
     * Prevents lost-update anomalies under concurrent state transitions
     * (e.g. two requests trying to cancel the same booking simultaneously).
     */
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    // ── Domain helpers ────────────────────────────────────────────────────

    public boolean isCancellable() {
        return status == BookingStatus.PENDING || status == BookingStatus.CONFIRMED;
    }

    public boolean isActive() {
        return status == BookingStatus.CONFIRMED || status == BookingStatus.CHECKED_IN;
    }

    public void cancel(String reason) {
        this.status = BookingStatus.CANCELLED;
        this.cancellationReason = reason;
    }

    public void markNoShow() {
        this.status = BookingStatus.NO_SHOW;
        this.cancellationReason = "Auto-cancelled: no check-in within grace period";
    }

    public void confirm(String qrToken) {
        this.status = BookingStatus.CONFIRMED;
        this.qrToken = qrToken;
    }

    public void checkIn() {
        this.status = BookingStatus.CHECKED_IN;
        this.checkInTime = Instant.now();
    }

    public void complete() {
        this.status = BookingStatus.COMPLETED;
        this.checkOutTime = Instant.now();
    }
}
