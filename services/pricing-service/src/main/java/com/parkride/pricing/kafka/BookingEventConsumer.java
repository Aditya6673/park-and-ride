package com.parkride.pricing.kafka;

import com.parkride.events.BookingEvent;
import com.parkride.pricing.service.OccupancyTrackingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

/**
 * Kafka consumer for the {@code booking-events} topic.
 *
 * <p>Updates the lot occupancy counter in Redis:
 * <ul>
 *   <li>{@code BOOKING_CONFIRMED} → increment occupancy for the lot</li>
 *   <li>{@code BOOKING_CANCELLED} / {@code BOOKING_NO_SHOW} → decrement occupancy</li>
 *   <li>All other event types are ignored</li>
 * </ul>
 *
 * <p>Offset is committed after processing (MANUAL ack mode) to prevent data loss.
 * Occupancy counters are eventually-consistent — slight drift is acceptable for
 * surge pricing decisions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingEventConsumer {

    private final OccupancyTrackingService occupancyService;

    @KafkaListener(
        topics           = "${spring.kafka.topics.booking-events}",
        groupId          = "${spring.kafka.consumer.group-id}",
        containerFactory = "bookingKafkaListenerContainerFactory"
    )
    public void consume(
            @Payload BookingEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET)             long offset,
            Acknowledgment ack) {

        log.debug("BookingEvent [type={}, lotId={}, partition={}, offset={}]",
                event.getEventType(), event.getLotId(), partition, offset);

        try {
            switch (event.getEventType()) {
                case BOOKING_CONFIRMED ->
                    occupancyService.incrementOccupancy(event.getLotId());
                case BOOKING_CANCELLED, BOOKING_NO_SHOW ->
                    occupancyService.decrementOccupancy(event.getLotId());
                default ->
                    log.trace("Ignoring BookingEvent type {} for pricing", event.getEventType());
            }
            ack.acknowledge();
        } catch (Exception ex) {
            log.error("Failed to update occupancy for BookingEvent [lotId={}, type={}]: {}",
                    event.getLotId(), event.getEventType(), ex.getMessage(), ex);
            // Do NOT acknowledge — Kafka will redeliver for retry
        }
    }
}
