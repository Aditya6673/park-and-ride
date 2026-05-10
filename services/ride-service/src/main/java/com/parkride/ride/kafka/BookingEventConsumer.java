package com.parkride.ride.kafka;

import com.parkride.events.BookingEvent;
import com.parkride.ride.domain.VehicleType;
import com.parkride.ride.service.RideService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Listens to {@code booking-events} and auto-suggests a ride on BOOKING_CONFIRMED.
 *
 * <p>Default pickup coords (28.6139°N, 77.2090°E — New Delhi) are used as a
 * placeholder until parking lots store their own GPS coordinates.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingEventConsumer {

    // Placeholder lot coordinates — replace with lot-specific GPS from Parking Service
    private static final double DEFAULT_LOT_LAT = 28.6139;
    private static final double DEFAULT_LOT_LNG = 77.2090;

    // Metro / transit hub — default drop-off
    private static final double DEFAULT_DEST_LAT = 28.6304;
    private static final double DEFAULT_DEST_LNG = 77.2177;

    private final RideService rideService;

    @KafkaListener(topics = "booking-events", groupId = "${spring.kafka.consumer.group-id}")
    public void onBookingEvent(BookingEvent event) {
        if (event.getEventType() != BookingEvent.EventType.BOOKING_CONFIRMED) {
            return;
        }

        log.info("Booking {} confirmed for user {} — auto-requesting ride",
                event.getBookingId(), event.getUserId());

        try {
            rideService.autoRequestFromBooking(
                    event.getUserId(),
                    event.getBookingId(),
                    DEFAULT_LOT_LAT, DEFAULT_LOT_LNG,
                    DEFAULT_DEST_LAT, DEFAULT_DEST_LNG,
                    VehicleType.SHUTTLE   // default vehicle; user can change on app
            );
        } catch (Exception ex) {
            // Non-fatal — user can manually request a ride from the app
            log.warn("Auto-ride request failed for booking {}: {}",
                    event.getBookingId(), ex.getMessage());
        }
    }
}
