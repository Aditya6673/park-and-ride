package com.parkride.notification.service;

import com.parkride.events.PaymentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

/**
 * Kafka consumer for the {@code payment-events} topic.
 *
 * <p>Listens to payment lifecycle events and routes them to the correct
 * email template via {@link NotificationDispatchService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final NotificationDispatchService dispatchService;

    @KafkaListener(
        topics = "${spring.kafka.topics.payment-events}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "paymentKafkaListenerContainerFactory"
    )
    public void consume(
            @Payload PaymentEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment ack) {

        log.debug("Received PaymentEvent [type={}, userId={}, partition={}, offset={}]",
                event.getEventType(), event.getUserId(), partition, offset);

        try {
            dispatchService.dispatch(event);
            ack.acknowledge();
        } catch (Exception ex) {
            log.error("Error dispatching notification for PaymentEvent [userId={}]: {}",
                    event.getUserId(), ex.getMessage(), ex);
            // Do NOT acknowledge — allows Kafka to redeliver
        }
    }
}
