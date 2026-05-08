package com.parkride.pricing.kafka;

import com.parkride.events.BookingEvent;
import com.parkride.pricing.service.OccupancyTrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.kafka.support.Acknowledgment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BookingEventConsumer}.
 * Mocks {@link OccupancyTrackingService} and {@link Acknowledgment}.
 */
class BookingEventConsumerTest {

    @Mock OccupancyTrackingService occupancyService;
    @Mock Acknowledgment           ack;

    @InjectMocks
    BookingEventConsumer consumer;

    UUID lotId;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        lotId = UUID.randomUUID();
    }

    private BookingEvent event(BookingEvent.EventType type) {
        return BookingEvent.builder()
                .bookingId(UUID.randomUUID()).userId(UUID.randomUUID())
                .lotId(lotId).slotId(UUID.randomUUID())
                .eventType(type).occurredAt(Instant.now())
                .startTime(Instant.now()).endTime(Instant.now().plusSeconds(3600))
                .amount(BigDecimal.valueOf(100))
                .userEmail("test@example.com").userName("Test").slotLabel("A-01")
                .build();
    }

    @Test
    @DisplayName("BOOKING_CONFIRMED → incrementOccupancy called and offset acknowledged")
    void confirmed_callsIncrementAndAck() {
        consumer.consume(event(BookingEvent.EventType.BOOKING_CONFIRMED), 0, 0L, ack);

        verify(occupancyService).incrementOccupancy(lotId);
        verify(ack).acknowledge();
        verifyNoMoreInteractions(occupancyService);
    }

    @Test
    @DisplayName("BOOKING_CANCELLED → decrementOccupancy called and offset acknowledged")
    void cancelled_callsDecrementAndAck() {
        consumer.consume(event(BookingEvent.EventType.BOOKING_CANCELLED), 0, 1L, ack);

        verify(occupancyService).decrementOccupancy(lotId);
        verify(ack).acknowledge();
        verifyNoMoreInteractions(occupancyService);
    }

    @Test
    @DisplayName("BOOKING_NO_SHOW → decrementOccupancy called and offset acknowledged")
    void noShow_callsDecrementAndAck() {
        consumer.consume(event(BookingEvent.EventType.BOOKING_NO_SHOW), 0, 2L, ack);

        verify(occupancyService).decrementOccupancy(lotId);
        verify(ack).acknowledge();
        verifyNoMoreInteractions(occupancyService);
    }

    @Test
    @DisplayName("SLOT_CHECKED_IN → no occupancy change, offset still acknowledged")
    void slotCheckedIn_noOccupancyChange_stillAcknowledged() {
        consumer.consume(event(BookingEvent.EventType.SLOT_CHECKED_IN), 0, 3L, ack);

        verifyNoInteractions(occupancyService);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("Service throws exception → offset NOT acknowledged (Kafka retries)")
    void serviceThrows_offsetNotAcknowledged() {
        doThrow(new RuntimeException("Redis unavailable"))
                .when(occupancyService).incrementOccupancy(any());

        consumer.consume(event(BookingEvent.EventType.BOOKING_CONFIRMED), 0, 4L, ack);

        verify(ack, never()).acknowledge();
    }
}
