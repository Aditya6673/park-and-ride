package com.parkride.ride.kafka;

import com.parkride.events.BookingEvent;
import com.parkride.ride.service.RideService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingEventConsumerTest {

    @Mock RideService rideService;

    @InjectMocks BookingEventConsumer consumer;

    @Test
    @DisplayName("BOOKING_CONFIRMED → autoRequestFromBooking called")
    void onBookingEvent_confirmed_triggersAutoRide() {
        BookingEvent event = BookingEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(BookingEvent.EventType.BOOKING_CONFIRMED)
                .bookingId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .build();

        consumer.onBookingEvent(event);

        verify(rideService).autoRequestFromBooking(
                eq(event.getUserId()),
                eq(event.getBookingId()),
                anyDouble(), anyDouble(),
                anyDouble(), anyDouble(),
                any()
        );
    }

    @Test
    @DisplayName("BOOKING_CANCELLED → no ride created")
    void onBookingEvent_cancelled_noAction() {
        BookingEvent event = BookingEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(BookingEvent.EventType.BOOKING_CANCELLED)
                .bookingId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .build();

        consumer.onBookingEvent(event);

        verify(rideService, never()).autoRequestFromBooking(any(), any(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), any());
    }

    @Test
    @DisplayName("autoRequestFromBooking throws → exception swallowed, no propagation")
    void onBookingEvent_rideServiceThrows_noExceptionPropagated() {
        BookingEvent event = BookingEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(BookingEvent.EventType.BOOKING_CONFIRMED)
                .bookingId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .build();

        doThrow(new RuntimeException("Kafka down")).when(rideService)
                .autoRequestFromBooking(any(), any(), anyDouble(), anyDouble(),
                        anyDouble(), anyDouble(), any());

        // Should not throw
        consumer.onBookingEvent(event);
    }
}
