package com.parkride.parking.service;

import com.parkride.parking.domain.*;
import com.parkride.parking.dto.CreateBookingRequest;
import com.parkride.parking.exception.SlotUnavailableException;
import com.parkride.parking.repository.BookingRepository;
import com.parkride.parking.repository.ParkingSlotRepository;
import com.parkride.parking.websocket.AvailabilityBroadcastService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SlotAssignmentService — unit tests")
// "null" — Eclipse @NonNull false positives on Mockito stubs (thenReturn, thenAnswer)
//          and entity getters (UUID, String) accessed through mock boundaries.
@SuppressWarnings("null")
class SlotAssignmentServiceTest {

    @Mock BookingRepository              bookingRepository;
    @Mock ParkingSlotRepository          slotRepository;
    @Mock AvailabilityService            availabilityService;
    @Mock QRCodeService                  qrCodeService;
    @Mock AvailabilityBroadcastService   broadcastService;
    @Mock RedissonClient                 redissonClient;
    @Mock KafkaTemplate<String, Object>  kafkaTemplate;
    @Mock RLock                          rLock;

    @InjectMocks SlotAssignmentService slotAssignmentService;

    // ── Fixtures ─────────────────────────────────────────────────────────

    private UUID userId;
    private ParkingLot lot;
    private ParkingSlot slot;
    private CreateBookingRequest request;
    private Instant startTime;
    private Instant endTime;

    @BeforeEach
    void setUp() {
        userId    = UUID.randomUUID();
        startTime = Instant.now().plusSeconds(3600);
        endTime   = Instant.now().plusSeconds(7200); // 1 hour later

        lot = ParkingLot.builder()
                .id(UUID.randomUUID())
                .name("Test Lot")
                .city("Delhi")
                .latitude(28.63)
                .longitude(77.21)
                .totalSlots(10)
                .active(true)
                .build();

        slot = ParkingSlot.builder()
                .id(UUID.randomUUID())
                .lot(lot)
                .slotNumber("A-001")
                .slotType(SlotType.CAR)
                .status(SlotStatus.AVAILABLE)
                .pricePerHour(BigDecimal.valueOf(50))
                .floor("G")
                .positionIndex(1)
                .active(true)
                .build();

        request = new CreateBookingRequest();
        request.setSlotId(slot.getId());
        request.setStartTime(startTime);
        request.setEndTime(endTime);
    }

    // ── Happy path ────────────────────────────────────────────────────────

    @Test
    @DisplayName("assignAndBook — happy path: acquires lock, books slot, returns CONFIRMED booking")
    void assignAndBook_happyPath() throws InterruptedException {
        // Arrange
        when(slotRepository.findById(slot.getId())).thenReturn(Optional.of(slot));
        when(availabilityService.getAvailableCount(lot.getId())).thenReturn(3L);
        when(slotRepository.findAvailableSlots(any(), any(), any(), any()))
                .thenReturn(List.of(slot));
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(qrCodeService.generateQrToken(any())).thenReturn("mocked-qr-token");

        // The booking is saved twice: first PENDING, then CONFIRMED
        Booking pendingBooking = Booking.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .slot(slot)
                .startTime(startTime)
                .endTime(endTime)
                .status(BookingStatus.PENDING)
                .totalAmount(BigDecimal.valueOf(50))
                .build();
        Booking confirmedBooking = Booking.builder()
                .id(pendingBooking.getId())
                .userId(userId)
                .slot(slot)
                .startTime(startTime)
                .endTime(endTime)
                .status(BookingStatus.CONFIRMED)
                .totalAmount(BigDecimal.valueOf(50))
                .qrToken("mocked-qr-token")
                .build();

        when(bookingRepository.save(any()))
                .thenReturn(pendingBooking)
                .thenReturn(confirmedBooking);
        when(slotRepository.save(any())).thenReturn(slot);
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(null);

        // Act
        Booking result = slotAssignmentService.assignAndBook(userId, request);

        // Assert
        assertThat(result.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(result.getQrToken()).isEqualTo("mocked-qr-token");
        verify(bookingRepository, times(2)).save(any());
        verify(slotRepository).save(slot);
        verify(availabilityService).refreshCache(lot.getId());
        verify(broadcastService).broadcastAvailability(eq(lot.getId()), anyLong());
        verify(rLock).unlock();
    }

    // ── Cache fast-reject ──────────────────────────────────────────────────

    @Test
    @DisplayName("assignAndBook — fast-rejects when Redis availability cache is 0")
    void assignAndBook_cacheZero_throws() {
        when(slotRepository.findById(slot.getId())).thenReturn(Optional.of(slot));
        when(availabilityService.getAvailableCount(lot.getId())).thenReturn(0L);

        assertThatThrownBy(() -> slotAssignmentService.assignAndBook(userId, request))
                .isInstanceOf(SlotUnavailableException.class)
                .hasMessageContaining("No slots available");

        verify(slotRepository, never()).findAvailableSlots(any(), any(), any(), any());
    }

    // ── No slots in DB ────────────────────────────────────────────────────

    @Test
    @DisplayName("assignAndBook — throws when DB returns no available slots for time window")
    void assignAndBook_noSlotsInDb_throws() {
        when(slotRepository.findById(slot.getId())).thenReturn(Optional.of(slot));
        when(availabilityService.getAvailableCount(lot.getId())).thenReturn(5L);
        when(slotRepository.findAvailableSlots(any(), any(), any(), any()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> slotAssignmentService.assignAndBook(userId, request))
                .isInstanceOf(SlotUnavailableException.class)
                .hasMessageContaining("No available slots");
    }

    // ── Lock timeout ──────────────────────────────────────────────────────

    @Test
    @DisplayName("assignAndBook — throws when Redisson lock cannot be acquired within timeout")
    void assignAndBook_lockTimeout_throws() throws InterruptedException {
        when(slotRepository.findById(slot.getId())).thenReturn(Optional.of(slot));
        when(availabilityService.getAvailableCount(lot.getId())).thenReturn(5L);
        when(slotRepository.findAvailableSlots(any(), any(), any(), any()))
                .thenReturn(List.of(slot));
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        assertThatThrownBy(() -> slotAssignmentService.assignAndBook(userId, request))
                .isInstanceOf(SlotUnavailableException.class)
                .hasMessageContaining("please try again");

        verify(bookingRepository, never()).save(any());
    }

    // ── TOCTOU: slot taken inside lock ────────────────────────────────────

    @Test
    @DisplayName("assignAndBook — throws when slot is taken between first check and lock acquisition (TOCTOU)")
    void assignAndBook_slotTakenInsideLock_throws() throws InterruptedException {
        when(slotRepository.findById(slot.getId())).thenReturn(Optional.of(slot));
        when(availabilityService.getAvailableCount(lot.getId())).thenReturn(5L);
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        // First call (pre-lock): slot is available
        // Second call (inside lock): slot is gone
        when(slotRepository.findAvailableSlots(any(), any(), any(), any()))
                .thenReturn(List.of(slot))    // pre-lock check
                .thenReturn(List.of());       // inside-lock re-verify

        assertThatThrownBy(() -> slotAssignmentService.assignAndBook(userId, request))
                .isInstanceOf(SlotUnavailableException.class)
                .hasMessageContaining("Slot was taken");

        // Lock must be released even on failure
        verify(rLock).unlock();
        verify(bookingRepository, never()).save(any());
    }

    // ── Amount calculation ────────────────────────────────────────────────

    @Test
    @DisplayName("assignAndBook — calculates amount correctly for 1.5-hour booking at ₹50/hr")
    void assignAndBook_calculatesAmountCorrectly() throws InterruptedException {
        // 1.5 hour window
        request.setStartTime(Instant.now().plusSeconds(3600));
        request.setEndTime(Instant.now().plusSeconds(3600 + 5400)); // +1.5h

        when(slotRepository.findById(slot.getId())).thenReturn(Optional.of(slot));
        when(availabilityService.getAvailableCount(lot.getId())).thenReturn(3L);
        when(slotRepository.findAvailableSlots(any(), any(), any(), any()))
                .thenReturn(List.of(slot));
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(qrCodeService.generateQrToken(any())).thenReturn("qr");
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(null);

        // Capture the booking passed to save
        Booking[] savedBooking = new Booking[1];
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> {
            savedBooking[0] = inv.getArgument(0);
            return savedBooking[0];
        });
        when(slotRepository.save(any())).thenReturn(slot);

        slotAssignmentService.assignAndBook(userId, request);

        // First save is the PENDING booking with the amount
        assertThat(savedBooking[0].getTotalAmount())
                .isEqualByComparingTo(new BigDecimal("75.00")); // 1.5h * ₹50
    }
}
