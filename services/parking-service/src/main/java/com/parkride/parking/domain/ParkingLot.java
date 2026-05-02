package com.parkride.parking.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Parking lot aggregate root.
 *
 * <p>One lot contains many {@link ParkingSlot}s. The lot is owned by an operator
 * identified by {@link #operatorId} — a UUID from the Auth Service. There is no
 * cross-service foreign key; ownership is enforced at the service level.
 *
 * <p>Geolocation uses decimal degrees (WGS84). Haversine-based proximity search
 * is performed in JPQL without PostGIS, sufficient for Phase 1 city-scale queries.
 */
@Entity
@Table(name = "parking_lots", indexes = {
        @Index(name = "idx_lots_city", columnList = "city"),
        @Index(name = "idx_lots_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParkingLot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Display name shown to users (e.g. "Connaught Place Parking Block A"). */
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "address", nullable = false, length = 500)
    private String address;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    @Column(name = "state", length = 100)
    private String state;

    /** WGS84 latitude — used for Haversine proximity queries. */
    @Column(name = "latitude", nullable = false)
    private Double latitude;

    /** WGS84 longitude — used for Haversine proximity queries. */
    @Column(name = "longitude", nullable = false)
    private Double longitude;

    /** Total physical slots in the lot (informational; actual availability is computed). */
    @Column(name = "total_slots", nullable = false)
    private int totalSlots;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Column(name = "description", length = 1000)
    private String description;

    /** Image URL for the lot (displayed on the map and booking wizard). */
    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /**
     * Operator/owner — UUID of the ROLE_OPERATOR user in Auth Service.
     * No cross-service FK; service validates ownership on mutations.
     */
    @Column(name = "operator_id")
    private UUID operatorId;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @OneToMany(mappedBy = "lot", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ParkingSlot> slots = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    // ── Domain helpers ────────────────────────────────────────────────────

    public long countActiveSlots() {
        return slots.stream().filter(ParkingSlot::isActive).count();
    }
}
