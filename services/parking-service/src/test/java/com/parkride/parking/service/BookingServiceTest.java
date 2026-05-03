package com.parkride.parking.service;

import com.parkride.parking.domain.*;
import com.parkride.parking.dto.BookingResponse;
import com.parkride.parking.dto.CreateBookingRequest;
import com.parkride.parking.exception.BookingNotFoundException;
import com.parkride.parking.exception.ParkingException;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookingService — unit tests")
// "null" — Eclipse @NonNull false positives on Mockito thenReturn stubs for
//          Booking, ParkingSlot, and List<Booking> return types.
@SuppressWarnings("null")
class BookingServiceTest {

    @Mock BookingRepository          bookingRepository;
    @Mock ParkingSlotRepository      slotRepository;
    @Mock SlotAssignmentService      slotAssignmentService;
    @Mock AvailabilityService        availabilityService;
    @Mock AvailabilityBroadcastService broadcastService;

    @InjectMocks BookingService bookingService;

    // ── Fixtures ─────────────────────────────────────────────────────────

    private UUID userId;
    private UUID bookingId;
    private ParkingLot lot;
    private ParkingSlot slot;
    private Booking confirmedBooking;
    private CreateBookingRequest request;

    @BeforeEach
    void setUp() {
        userId    = UUID.randomUUID();
        bookingId = UUID.randomUUID();

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
                .status(SlotStatus.RESERVED)
                .pricePerHour(BigDecimal.valueOf(50))
                .floor("G")
                .positionIndex(1)
                .active(true)
                .build();

        confirmedBooking = Booking.builder()
                .id(bookingId)
                .userId(userId)
                .slot(slot)
                .startTime(Instant.now().plusSeconds(3600))
                .endTime(Instant.now().plusSeconds(7200))
                .status(BookingStatus.CONFIRMED)
                .totalAmount(BigDecimal.valueOf(100))
                .qrToken("mock-qr-token")
                .build();

        request = new CreateBookingRequest();
        request.setSlotId(slot.getId());
        request.setStartTime(Instant.now().plusSeconds(3600));
        request.setEndTime(Instant.now().plusSeconds(7200));
    }

    // ── createBooking ─────────────────────────────────────────────────────

    @Test
    @DisplayName("createBooking — happy path: delegates to SlotAssignmentService and returns response")
    void createBooking_happyPath() {
        when(bookingRepository.countActiveBookings(userId)).thenReturn(0L);
        when(slotAssignmentService.assignAndBook(userId, request)).thenReturn(confirmedBooking);

        BookingResponse response = bookingService.createBooking(userId, request);

        assertThat(response.getId()).isEqualTo(bookingId);
        assertThat(response.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(slotAssignmentService).assignAndBook(userId, request);
    }

    @Test
    @DisplayName("createBooking — rejects when user already has 3 active bookings")
    void createBooking_exceedsMaxActive_throws() {
        when(bookingRepository.countActiveBookings(userId)).thenReturn(3L);

        assertThatThrownBy(() -> bookingService.createBooking(userId, request))
                .isInstanceOf(ParkingException.class)
                .hasMessageContaining("Maximum of 3 active bookings");

        verify(slotAssignmentService, never()).assignAndBook(any(), any());
    }

    @Test
    @DisplayName("createBooking — rejects booking window less than 30 minutes")
    void createBooking_tooShortWindow_throws() {
        request.setStartTime(Instant.now().plusSeconds(3600));
        request.setEndTime(Instant.now().plusSeconds(3600 + 1200)); // only 20 min

        assertThatThrownBy(() -> bookingService.createBooking(userId, request))
                .isInstanceOf(ParkingException.class)
                .hasMessageContaining("Minimum booking duration");
    }

    @Test
    @DisplayName("createBooking — rejects booking window longer than 24 hours")
    void createBooking_tooLongWindow_throws() {
        request.setStartTime(Instant.now().plusSeconds(3600));
        request.setEndTime(Instant.now().plusSeconds(3600 + 25 * 3600)); // 25 hours

        assertThatThrownBy(() -> bookingService.createBooking(userId, request))
                .isInstanceOf(ParkingException.class)
                .hasMessageContaining("Maximum booking duration");
    }

    // ── getBooking ────────────────────────────────────────────────────────

    @Test
    @DisplayName("getBooking — returns booking when found and belongs to user")
    void getBooking_found() {
        when(bookingRepository.findByIdAndUserId(bookingId, userId))
                .thenReturn(Optional.of(confirmedBooking));

        BookingResponse response = bookingService.getBooking(bookingId, userId);

        assertThat(response.getId()).isEqualTo(bookingId);
        assertThat(response.getUserId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("getBooking — throws BookingNotFoundException when not found or wrong user")
    void getBooking_notFound_throws() {
        when(bookingRepository.findByIdAndUserId(bookingId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.getBooking(bookingId, userId))
                .isInstanceOf(BookingNotFoundException.class);
    }

    // ── cancelBooking ─────────────────────────────────────────────────────

    @Test
    @DisplayName("cancelBooking — cancels CONFIRMED booking, releases slot, refreshes cache")
    void cancelBooking_confirmed_success() {
        when(bookingRepository.findByIdAndUserId(bookingId, userId))
                .thenReturn(Optional.of(confirmedBooking));
        when(bookingRepository.save(any())).thenReturn(confirmedBooking);
        when(slotRepository.save(any())).thenReturn(slot);
        when(availabilityService.getAvailableCount(any())).thenReturn(5L);

        bookingService.cancelBooking(bookingId, userId);

        assertThat(confirmedBooking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(slotRepository).save(slot);
        verify(availabilityService).refreshCache(lot.getId());
        verify(broadcastService).broadcastAvailability(eq(lot.getId()), eq(5L));
    }

    @Test
    @DisplayName("cancelBooking — throws ParkingException for already CHECKED_IN booking")
    void cancelBooking_checkedIn_throws() {
        confirmedBooking.checkIn();
        when(bookingRepository.findByIdAndUserId(bookingId, userId))
                .thenReturn(Optional.of(confirmedBooking));

        assertThatThrownBy(() -> bookingService.cancelBooking(bookingId, userId))
                .isInstanceOf(ParkingException.class)
                .hasMessageContaining("Cannot cancel");
    }

    // ── getUserBookings ───────────────────────────────────────────────────

    @Test
    @DisplayName("getUserBookings — returns paginated bookings for user")
    void getUserBookings_returnsPaginatedResults() {
        var pageable = PageRequest.of(0, 10);
        when(bookingRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable))
                .thenReturn(new PageImpl<>(List.of(confirmedBooking)));

        var page = bookingService.getUserBookings(userId, pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().getFirst().getId()).isEqualTo(bookingId);
    }
}
