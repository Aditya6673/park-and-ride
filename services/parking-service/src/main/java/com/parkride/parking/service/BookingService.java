package com.parkride.parking.service;

import com.parkride.events.BookingEvent;
import com.parkride.parking.domain.Booking;
import com.parkride.parking.dto.BookingResponse;
import com.parkride.parking.dto.CreateBookingRequest;
import com.parkride.parking.exception.BookingNotFoundException;
import com.parkride.parking.exception.ParkingException;
import com.parkride.parking.repository.BookingRepository;
import com.parkride.parking.repository.ParkingSlotRepository;
import com.parkride.parking.websocket.AvailabilityBroadcastService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the full booking lifecycle:
 * create → confirm → check-in → complete / cancel / no-show.
 *
 * <p>Slot assignment is delegated to {@link SlotAssignmentService} which handles
 * locking and atomic reservation. This service handles the surrounding CRUD,
 * cancellation logic, and scheduled housekeeping jobs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
// "null" — Eclipse @NonNull false positives on Hibernate-populated entity fields
//          accessed through booking.getSlot() chains after Optional.orElseThrow().
@SuppressWarnings("null")
public class BookingService {

    /** Maximum active bookings per user — prevents hoarding. */
    private static final int MAX_ACTIVE_BOOKINGS = 3;

    /** Grace period after startTime before a booking is considered NO_SHOW. */
    private static final int NO_SHOW_GRACE_MINUTES = 30;

    private final BookingRepository       bookingRepository;
    private final ParkingSlotRepository   slotRepository;
    private final SlotAssignmentService   slotAssignmentService;
    private final AvailabilityService     availabilityService;
    private final AvailabilityBroadcastService broadcastService;

    // ── Create ────────────────────────────────────────────────────────────

    @Transactional
    public BookingResponse createBooking(UUID userId, CreateBookingRequest request) {
        validateBookingWindow(request);

        long active = bookingRepository.countActiveBookings(userId);
        if (active >= MAX_ACTIVE_BOOKINGS) {
            throw new ParkingException(
                    "Maximum of " + MAX_ACTIVE_BOOKINGS + " active bookings allowed per user");
        }

        Booking booking = slotAssignmentService.assignAndBook(userId, request);
        return BookingResponse.from(booking);
    }

    // ── Read ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public BookingResponse getBooking(UUID bookingId, UUID userId) {
        Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        return BookingResponse.from(booking);
    }

    @Transactional(readOnly = true)
    public Page<BookingResponse> getUserBookings(UUID userId, Pageable pageable) {
        return bookingRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(BookingResponse::from);
    }

    // ── Cancel ────────────────────────────────────────────────────────────

    @Transactional
    public void cancelBooking(UUID bookingId, UUID userId) {
        Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (!booking.isCancellable()) {
            throw new ParkingException(
                    "Cannot cancel a booking in " + booking.getStatus() + " status");
        }

        booking.cancel("Cancelled by user");
        bookingRepository.save(booking);

        // Release the slot
        booking.getSlot().markAvailable();
        slotRepository.save(booking.getSlot());

        UUID lotId = booking.getSlot().getLot().getId();
        availabilityService.refreshCache(lotId);
        broadcastService.broadcastAvailability(lotId, availabilityService.getAvailableCount(lotId));
        slotAssignmentService.publishBookingEvent(booking, BookingEvent.EventType.BOOKING_CANCELLED);

        log.info("Booking {} cancelled by user {}", bookingId, userId);
    }

    // ── QR / Check-in ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public String getQrToken(UUID bookingId, UUID userId) {
        Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (booking.getQrToken() == null) {
            throw new ParkingException("QR code not yet generated for this booking");
        }
        return booking.getQrToken();
    }

    // ── Scheduled housekeeping ────────────────────────────────────────────

    /**
     * Auto-cancels CONFIRMED bookings where the user failed to check in
     * within 30 minutes of the start time.
     *
     * <p>Runs every 15 minutes. Uses a grace period of 30 minutes.
     */
    @Scheduled(fixedDelay = 15 * 60 * 1000) // every 15 minutes
    @Transactional
    public void autoCancelNoShows() {
        Instant cutoff = Instant.now().minus(NO_SHOW_GRACE_MINUTES, ChronoUnit.MINUTES);
        List<Booking> noShows = bookingRepository.findNoShowCandidates(cutoff);

        if (noShows.isEmpty()) return;

        log.info("Auto-cancelling {} no-show bookings", noShows.size());

        for (Booking booking : noShows) {
            booking.markNoShow();
            bookingRepository.save(booking);

            booking.getSlot().markAvailable();
            slotRepository.save(booking.getSlot());

            UUID lotId = booking.getSlot().getLot().getId();
            availabilityService.refreshCache(lotId);
            broadcastService.broadcastAvailability(lotId, availabilityService.getAvailableCount(lotId));
            slotAssignmentService.publishBookingEvent(booking, BookingEvent.EventType.BOOKING_NO_SHOW);
        }
    }

    /**
     * Cleans up stale PENDING bookings older than 15 minutes
     * (payment not completed — user abandoned checkout).
     * Runs every 10 minutes.
     */
    @Scheduled(fixedDelay = 10 * 60 * 1000)
    @Transactional
    public void cleanupStalePending() {
        Instant cutoff = Instant.now().minus(15, ChronoUnit.MINUTES);
        int count = bookingRepository.cancelStalePendingBookings(cutoff);
        if (count > 0) {
            log.info("Cleaned up {} stale PENDING bookings", count);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void validateBookingWindow(CreateBookingRequest request) {
        if (!request.getEndTime().isAfter(request.getStartTime())) {
            throw new ParkingException("End time must be after start time");
        }
        long minutes = java.time.Duration.between(request.getStartTime(), request.getEndTime()).toMinutes();
        if (minutes < 30) {
            throw new ParkingException("Minimum booking duration is 30 minutes");
        }
        if (minutes > 24 * 60) {
            throw new ParkingException("Maximum booking duration is 24 hours");
        }
    }
}
