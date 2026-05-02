package com.parkride.parking.repository;

import com.parkride.parking.domain.ParkingSlot;
import com.parkride.parking.domain.SlotStatus;
import com.parkride.parking.domain.SlotType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ParkingSlotRepository extends JpaRepository<ParkingSlot, UUID> {

    List<ParkingSlot> findByLotIdAndActiveTrue(UUID lotId);

    List<ParkingSlot> findByLotIdAndSlotTypeAndActiveTrue(UUID lotId, SlotType slotType);

    long countByLotIdAndStatus(UUID lotId, SlotStatus status);

    /**
     * Finds slots in a lot that have no active (non-cancelled/no-show) booking
     * overlapping the requested time window.
     *
     * <p>Results are ordered by floor and position_index so the assignment
     * service naturally picks the closest-to-entrance slot first.
     */
    @Query("""
            SELECT s FROM ParkingSlot s
            WHERE s.lot.id = :lotId
            AND s.active = true
            AND s.status = 'AVAILABLE'
            AND (:slotType IS NULL OR s.slotType = :slotType)
            AND NOT EXISTS (
                SELECT 1 FROM Booking b
                WHERE b.slot = s
                AND b.status NOT IN ('CANCELLED', 'NO_SHOW')
                AND b.startTime < :endTime
                AND b.endTime   > :startTime
            )
            ORDER BY s.floor ASC NULLS LAST, s.positionIndex ASC
            """)
    List<ParkingSlot> findAvailableSlots(@Param("lotId")    UUID lotId,
                                         @Param("slotType") SlotType slotType,
                                         @Param("startTime") Instant startTime,
                                         @Param("endTime")   Instant endTime);

    @Modifying
    @Query("UPDATE ParkingSlot s SET s.status = :status WHERE s.id = :slotId")
    int updateSlotStatus(@Param("slotId") UUID slotId, @Param("status") SlotStatus status);
}
