package com.parkride.parking.service;

import com.parkride.events.BookingEvent;
import com.parkride.parking.domain.Booking;
import com.parkride.parking.domain.BookingStatus;
import com.parkride.parking.domain.ParkingSlot;
import com.parkride.parking.dto.CreateBookingRequest;
import com.parkride.parking.exception.ParkingException;
import com.parkride.parking.exception.SlotUnavailableException;
import com.parkride.parking.repository.BookingRepository;
import com.parkride.parking.repository.ParkingSlotRepository;
import com.parkride.parking.websocket.AvailabilityBroadcastService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Implements the 10-step distributed slot assignment algorithm:
 *
 * <ol>
 *   <li>Check Redis availability cache — fast reject if no slots available</li>
 *   <li>Query available slots from DB for the time window</li>
 *   <li>Reject if none available</li>
 *   <li>Score slots by: floor proximity, position index, slot type match</li>
 *   <li>Acquire Redisson distributed lock on the best slot UUID (5s timeout)</li>
 *   <li>Re-verify slot is still free inside the lock (TOCTOU prevention)</li>
 *   <li>Calculate total booking amount</li>
 *   <li>Persist CONFIRMED Booking entity with generated QR token</li>
 *   <li>Update slot status to RESERVED in DB</li>
 *  <li>Invalidate Redis availability cache + broadcast via WebSocket + publish Kafka event</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
// "null" — Eclipse @NonNull false positives on Mockito-like patterns:
//          booking.getId() after repository.save(), UUID.toString(), and
//          slotRepository.findById().map() inside the assignment algorithm.
@SuppressWarnings("null")
public class SlotAssignmentService {

    private static final String SLOT_LOCK_PREFIX   = "slot-lock:";
    private static final long   LOCK_WAIT_SECONDS  = 5;
    private static final long   LOCK_LEASE_SECONDS = 10;

    private final BookingRepository          bookingRepository;
    private final ParkingSlotRepository      slotRepository;
    private final AvailabilityService        availabilityService;
    private final QRCodeService              qrCodeService;
    private final AvailabilityBroadcastService broadcastService;
    private final RedissonClient             redissonClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional
    public Booking assignAndBook(UUID userId, CreateBookingRequest request) {
        // Step 1 — fast cache check
        UUID lotId = slotRepository.findById(request.getSlotId())
                .map(s -> s.getLot().getId())
                .orElseThrow(() -> new SlotUnavailableException("Slot not found: " + request.getSlotId()));

        long cached = availabilityService.getAvailableCount(lotId);
        if (cached == 0) {
            throw new SlotUnavailableException("No slots available in this lot");
        }

        // Step 2 — load available candidates from DB
        List<ParkingSlot> candidates = slotRepository.findAvailableSlots(
                lotId,
                request.getPreferredSlotType(),
                request.getStartTime(),
                request.getEndTime()
        );

        // Step 3 — reject if empty
        if (candidates.isEmpty()) {
            throw new SlotUnavailableException("No available slots for the requested time window");
        }

        // Step 4 — score and pick best slot
        // If the user requested a specific slot, prefer it; otherwise use scored list
        ParkingSlot targetSlot = candidates.stream()
                .filter(s -> s.getId().equals(request.getSlotId()))
                .findFirst()
                .orElse(candidates.getFirst()); // first candidate is already position-ordered

        // Step 5 — acquire distributed lock on slot UUID
        RLock lock = redissonClient.getLock(SLOT_LOCK_PREFIX + targetSlot.getId());
        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ParkingException("Interrupted while acquiring slot lock");
        }

        if (!acquired) {
            throw new SlotUnavailableException("Slot is being reserved by another user — please try again");
        }

        try {
            // Step 6 — re-verify inside lock (TOCTOU prevention)
            List<ParkingSlot> stillAvailable = slotRepository.findAvailableSlots(
                    lotId, request.getPreferredSlotType(),
                    request.getStartTime(), request.getEndTime()
            );
            boolean targetStillFree = stillAvailable.stream()
                    .anyMatch(s -> s.getId().equals(targetSlot.getId()));
            if (!targetStillFree) {
                throw new SlotUnavailableException("Slot was taken — please select another");
            }

            // Step 7 — calculate amount
            long hours     = Duration.between(request.getStartTime(), request.getEndTime()).toHours();
            long minutes   = Duration.between(request.getStartTime(), request.getEndTime()).toMinutesPart();
            BigDecimal hrs = BigDecimal.valueOf(hours).add(
                    BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));
            BigDecimal amount = targetSlot.getPricePerHour().multiply(hrs).setScale(2, RoundingMode.HALF_UP);

            // Step 8 — build and persist booking (PENDING → will confirm with QR next)
            Booking booking = Booking.builder()
                    .userId(userId)
                    .slot(targetSlot)
                    .startTime(request.getStartTime())
                    .endTime(request.getEndTime())
                    .status(BookingStatus.PENDING)
                    .totalAmount(amount)
                    .build();

            booking = bookingRepository.save(booking);

            // Generate QR token and confirm
            String qrToken = qrCodeService.generateQrToken(booking);
            booking.confirm(qrToken);
            booking = bookingRepository.save(booking);

            // Step 9 — update slot status
            targetSlot.markReserved();
            slotRepository.save(targetSlot);

            // Step 10 — refresh cache, broadcast, publish event
            availabilityService.refreshCache(lotId);
            broadcastService.broadcastAvailability(lotId, availabilityService.getAvailableCount(lotId));
            publishBookingEvent(booking, BookingEvent.EventType.BOOKING_CONFIRMED);

            log.info("Booking {} confirmed for user {} on slot {} (lot {})",
                    booking.getId(), userId, targetSlot.getSlotNumber(), lotId);
            return booking;

        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // ── Kafka event publishing ─────────────────────────────────────────────

    public void publishBookingEvent(Booking booking, BookingEvent.EventType eventType) {
        try {
            // Extract user email from SecurityContext (stored by JwtAuthFilter).
            // Falls back to null for scheduled jobs (no HTTP context).
            String userEmail = extractEmailFromSecurityContext();
            String slotLabel = booking.getSlot().getSlotNumber()
                    + " / " + booking.getSlot().getFloor() + " Floor";

            BookingEvent event = BookingEvent.builder()
                    .eventId(UUID.randomUUID())
                    .eventType(eventType)
                    .occurredAt(Instant.now())
                    .bookingId(booking.getId())
                    .userId(booking.getUserId())
                    .slotId(booking.getSlot().getId())
                    .lotId(booking.getSlot().getLot().getId())
                    .status(booking.getStatus().name())
                    .amount(booking.getTotalAmount())
                    .startTime(booking.getStartTime())
                    .endTime(booking.getEndTime())
                    .qrCodeToken(booking.getQrToken())
                    .userEmail(userEmail)
                    .slotLabel(slotLabel)
                    .build();

            kafkaTemplate.send("booking-events", booking.getUserId().toString(), event);
            log.debug("Published {} event for booking {}", eventType, booking.getId());
        } catch (Exception e) {
            // Non-fatal — booking is already persisted; event can be replayed
            log.error("Failed to publish Kafka event for booking {}: {}", booking.getId(), e.getMessage(), e);
        }
    }

    private String extractEmailFromSecurityContext() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getDetails() == null) return null;
        if (auth.getDetails() instanceof java.util.Map<?, ?> detailsMap) {
            Object email = detailsMap.get("email");
            String emailStr = email instanceof String s ? s : null;
            return (emailStr != null && !emailStr.isBlank()) ? emailStr : null;
        }
        return null;
    }
}
