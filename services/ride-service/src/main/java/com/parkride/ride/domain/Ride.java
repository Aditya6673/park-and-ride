package com.parkride.ride.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A ride request from a user — the core aggregate of the Ride Service.
 *
 * <p>State machine:
 * <pre>
 *   REQUESTED → DRIVER_ASSIGNED → DRIVER_ARRIVED → IN_PROGRESS → COMPLETED
 *          ↘                                                    ↗
 *           ─────────────────── CANCELLED ──────────────────────
 * </pre>
 *
 * <p>Optimistic locking via {@link #version} prevents concurrent state
 * transitions (e.g. simultaneous cancel + driver assignment).
 */
@Entity
@Table(name = "rides", indexes = {
        @Index(name = "idx_rides_user_id",   columnList = "user_id"),
        @Index(name = "idx_rides_driver_id", columnList = "driver_id"),
        @Index(name = "idx_rides_status",    columnList = "status"),
        @Index(name = "idx_rides_booking",   columnList = "booking_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ride {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Auth-service user UUID — the passenger. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * Optional link to a Parking Service booking.
     * When present, the pickup coordinates match the parking lot location.
     */
    @Column(name = "booking_id")
    private UUID bookingId;

    /** Assigned driver — null until DRIVER_ASSIGNED. */
    @Column(name = "driver_id")
    private UUID driverId;

    /** Assigned vehicle UUID in the driver's fleet record. */
    @Column(name = "vehicle_id")
    private UUID vehicleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 20)
    private VehicleType vehicleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private RideStatus status = RideStatus.REQUESTED;

    // ── Pickup location ───────────────────────────────────────────────────

    @Column(name = "pickup_lat", nullable = false)
    private Double pickupLat;

    @Column(name = "pickup_lng", nullable = false)
    private Double pickupLng;

    @Column(name = "pickup_address", length = 300)
    private String pickupAddress;

    // ── Drop-off location ─────────────────────────────────────────────────

    @Column(name = "dropoff_lat", nullable = false)
    private Double dropoffLat;

    @Column(name = "dropoff_lng", nullable = false)
    private Double dropoffLng;

    @Column(name = "dropoff_address", length = 300)
    private String dropoffAddress;

    // ── Fare ─────────────────────────────────────────────────────────────

    /** Fare estimated at request time from the Pricing Service. */
    @Column(name = "estimated_fare", precision = 10, scale = 2)
    private BigDecimal estimatedFare;

    /** Actual fare set when the ride completes. */
    @Column(name = "final_fare", precision = 10, scale = 2)
    private BigDecimal finalFare;

    /** Distance travelled in km — populated at completion. */
    @Column(name = "distance_km")
    private Double distanceKm;

    // ── Pool ─────────────────────────────────────────────────────────────

    @Column(name = "is_pooled", nullable = false)
    @Builder.Default
    private Boolean isPooled = false;

    @Column(name = "pool_group_id")
    private UUID poolGroupId;

    // ── Rating ───────────────────────────────────────────────────────────

    /** Passenger's rating for this ride (1–5). Set after completion. */
    @Column(name = "passenger_rating")
    private Integer passengerRating;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    // ── Timestamps ────────────────────────────────────────────────────────

    @Column(name = "requested_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant requestedAt = Instant.now();

    /** When the driver marked arrival at the pickup point. */
    @Column(name = "pickup_at")
    private Instant pickupAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

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
        return status == RideStatus.REQUESTED || status == RideStatus.DRIVER_ASSIGNED;
    }

    public boolean isOwnedBy(UUID userId) {
        return this.userId.equals(userId);
    }

    public void assignDriver(UUID driverId, UUID vehicleId) {
        this.driverId  = driverId;
        this.vehicleId = vehicleId;
        this.status    = RideStatus.DRIVER_ASSIGNED;
    }

    public void markDriverArrived() {
        this.status   = RideStatus.DRIVER_ARRIVED;
        this.pickupAt = Instant.now();
    }

    public void start() {
        this.status     = RideStatus.IN_PROGRESS;
        this.startedAt  = Instant.now();
    }

    public void complete(BigDecimal finalFare, double distanceKm) {
        this.status      = RideStatus.COMPLETED;
        this.finalFare   = finalFare;
        this.distanceKm  = distanceKm;
        this.completedAt = Instant.now();
    }

    public void cancel(String reason) {
        this.status             = RideStatus.CANCELLED;
        this.cancellationReason = reason;
    }

    public void rate(int rating) {
        this.passengerRating = rating;
    }
}
