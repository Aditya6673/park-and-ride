package com.parkride.notification.service;

import com.parkride.events.BookingEvent;
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
 * <p>Delegates to {@link NotificationDispatchService} for routing.
 * On success → acknowledges (commits offset).
 * On failure → logs error but still acknowledges to avoid infinite retry loops
 * on bad messages. Transient failures (SMTP down) will be retried by the
 * consumer not acknowledging on unhandled exceptions from the dispatch service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingEventConsumer {

    private final NotificationDispatchService dispatchService;

    @KafkaListener(
        topics = "${spring.kafka.topics.booking-events}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "bookingKafkaListenerContainerFactory"
    )
    public void consume(
            @Payload BookingEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment ack) {

        log.debug("Received BookingEvent [type={}, bookingId={}, partition={}, offset={}]",
                event.getEventType(), event.getBookingId(), partition, offset);

        try {
            dispatchService.dispatch(event);
            ack.acknowledge();
        } catch (Exception ex) {
            log.error("Error dispatching notification for BookingEvent [bookingId={}]: {}",
                    event.getBookingId(), ex.getMessage(), ex);
            // Do NOT acknowledge — Kafka will redeliver for retry
        }
    }
}
