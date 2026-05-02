package com.parkride.parking.repository;

import com.parkride.parking.domain.Booking;
import com.parkride.parking.domain.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Page<Booking> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<Booking> findByQrToken(String qrToken);

    Optional<Booking> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Returns CONFIRMED bookings whose start time has passed the no-show grace period
     * (startTime + 30 minutes) and have not yet been checked in.
     * Called by the scheduled auto-cancel job every 15 minutes.
     */
    @Query("""
            SELECT b FROM Booking b
            WHERE b.status = 'CONFIRMED'
            AND b.startTime < :cutoff
            AND b.checkInTime IS NULL
            """)
    List<Booking> findNoShowCandidates(@Param("cutoff") Instant cutoff);

    /**
     * Bulk-cancels PENDING bookings older than {@code cutoff} that were never confirmed.
     * Used for cleaning up abandoned checkout sessions.
     */
    @Modifying
    @Query("""
            UPDATE Booking b SET b.status = 'CANCELLED',
                                 b.cancellationReason = 'Auto-cancelled: payment not completed'
            WHERE b.status = 'PENDING'
            AND b.createdAt < :cutoff
            """)
    int cancelStalePendingBookings(@Param("cutoff") Instant cutoff);

    /** Counts active (non-terminal) bookings for a user — used for booking limit checks. */
    @Query("""
            SELECT COUNT(b) FROM Booking b
            WHERE b.userId = :userId
            AND b.status IN ('PENDING', 'CONFIRMED', 'CHECKED_IN')
            """)
    long countActiveBookings(@Param("userId") UUID userId);
}
