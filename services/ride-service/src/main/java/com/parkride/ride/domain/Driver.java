package com.parkride.ride.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A registered driver who can be assigned to ride requests.
 *
 * <p>Cross-service identity: {@link #userId} maps to an Auth Service user with
 * ROLE_DRIVER. There is no FK — identity validated via JWT claim.
 */
@Entity
@Table(name = "drivers", indexes = {
        @Index(name = "idx_drivers_user_id",      columnList = "user_id"),
        @Index(name = "idx_drivers_status",        columnList = "status"),
        @Index(name = "idx_drivers_vehicle_type",  columnList = "vehicle_type")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Driver {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Auth-service user UUID for this driver. */
    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** E.164 phone number, e.g. +919876543210 */
    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "license_number", nullable = false, unique = true, length = 50)
    private String licenseNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 20)
    private VehicleType vehicleType;

    @Column(name = "vehicle_plate", nullable = false, length = 20)
    private String vehiclePlate;

    @Column(name = "vehicle_model", length = 100)
    private String vehicleModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private DriverStatus status = DriverStatus.OFFLINE;

    /** Last known latitude (WGS84). Updated by the driver app periodically. */
    @Column(name = "current_lat")
    private Double currentLat;

    /** Last known longitude (WGS84). */
    @Column(name = "current_lng")
    private Double currentLng;

    /** Epoch millis of the last GPS update. Used to detect stale locations. */
    @Column(name = "location_updated_at")
    private Instant locationUpdatedAt;

    /** Running average rating (0.0 – 5.0). Recalculated on each completed ride. */
    @Column(name = "rating", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal rating = BigDecimal.ZERO;

    @Column(name = "total_rides", nullable = false)
    @Builder.Default
    private Integer totalRides = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    // ── Domain helpers ────────────────────────────────────────────────────

    public boolean isAvailable() {
        return status == DriverStatus.AVAILABLE
                && currentLat != null
                && currentLng != null;
    }

    /** Recalculates running average after a new rating is submitted. */
    public void addRating(BigDecimal newRating) {
        if (totalRides == 0) {
            this.rating = newRating;
        } else {
            // running average: (old_avg * n + new_rating) / (n + 1)
            this.rating = rating.multiply(BigDecimal.valueOf(totalRides))
                    .add(newRating)
                    .divide(BigDecimal.valueOf(totalRides + 1L), 2, java.math.RoundingMode.HALF_UP);
        }
        this.totalRides++;
    }

    public void goOnline() {
        this.status = DriverStatus.AVAILABLE;
    }

    public void goOffline() {
        this.status = DriverStatus.OFFLINE;
    }

    public void startRide() {
        this.status = DriverStatus.ON_RIDE;
    }

    public void finishRide() {
        this.status = DriverStatus.AVAILABLE;
    }

    public void updateLocation(double lat, double lng) {
        this.currentLat = lat;
        this.currentLng = lng;
        this.locationUpdatedAt = Instant.now();
    }
}
