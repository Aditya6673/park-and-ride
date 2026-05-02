package com.parkride.parking.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An individual parking bay within a {@link ParkingLot}.
 *
 * <p>Status transitions:
 * <pre>
 * AVAILABLE ↔ RESERVED (on booking confirmed / cancelled)
 * RESERVED  → OCCUPIED  (on QR check-in)
 * OCCUPIED  → AVAILABLE (on check-out / booking completion)
 * Any       → MAINTENANCE (operator action)
 * </pre>
 *
 * <p>The {@link #status} column reflects the current real-time state.
 * It is also written to Redis by {@code AvailabilityService} for fast reads.
 */
@Entity
@Table(name = "parking_slots", indexes = {
        @Index(name = "idx_slots_lot_status", columnList = "lot_id, status"),
        @Index(name = "idx_slots_lot_type",   columnList = "lot_id, slot_type")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParkingSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lot_id", nullable = false)
    private ParkingLot lot;

    /** Human-readable identifier (e.g. "A-001", "B2-042"). */
    @Column(name = "slot_number", nullable = false, length = 20)
    private String slotNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "slot_type", nullable = false, length = 20)
    @Builder.Default
    private SlotType slotType = SlotType.CAR;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private SlotStatus status = SlotStatus.AVAILABLE;

    /** Price per hour for this specific slot (may vary by type). */
    @Column(name = "price_per_hour", nullable = false, precision = 10, scale = 2)
    private BigDecimal pricePerHour;

    /** Floor/level identifier (e.g. "G", "1", "B1"). Null for surface lots. */
    @Column(name = "floor", length = 10)
    private String floor;

    /**
     * Slot ordering within its floor — lower number means closer to entrance.
     * Used by {@code SlotAssignmentService} scoring to prefer nearest-to-entrance slots.
     */
    @Column(name = "position_index")
    @Builder.Default
    private int positionIndex = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    // ── Domain helpers ────────────────────────────────────────────────────

    public boolean isAvailable() {
        return status == SlotStatus.AVAILABLE && active;
    }

    public void markReserved() {
        this.status = SlotStatus.RESERVED;
    }

    public void markOccupied() {
        this.status = SlotStatus.OCCUPIED;
    }

    public void markAvailable() {
        this.status = SlotStatus.AVAILABLE;
    }
}
