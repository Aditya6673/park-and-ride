package com.parkride.notification.service;

import com.parkride.events.UserEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

/**
 * Kafka consumer for the {@code user-events} topic.
 *
 * <p>Handles user lifecycle events published by the Auth Service:
 * <ul>
 *   <li>{@link UserEvent.EventType#USER_REGISTERED} — sends welcome + email verification link</li>
 *   <li>{@link UserEvent.EventType#PASSWORD_RESET_REQUESTED} — sends password reset link email</li>
 * </ul>
 *
 * <p>On success → offset is acknowledged (committed).
 * On failure → offset is NOT acknowledged so Kafka redelivers for retry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserEventConsumer {

    private final NotificationDispatchService dispatchService;

    @KafkaListener(
        topics     = "${spring.kafka.topics.user-events:user-events}",
        groupId    = "${spring.kafka.consumer.group-id}",
        containerFactory = "userKafkaListenerContainerFactory"
    )
    public void consume(
            @Payload UserEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment ack) {

        log.debug("Received UserEvent [type={}, userId={}, partition={}, offset={}]",
                event.getEventType(), event.getUserId(), partition, offset);

        try {
            dispatchService.dispatch(event);
            ack.acknowledge();
        } catch (Exception ex) {
            log.error("Error dispatching notification for UserEvent [userId={}, type={}]: {}",
                    event.getUserId(), event.getEventType(), ex.getMessage(), ex);
            // Do NOT acknowledge — Kafka will redeliver for retry
        }
    }
}
