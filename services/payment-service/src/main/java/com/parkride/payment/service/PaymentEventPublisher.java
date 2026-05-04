package com.parkride.payment.service;

import com.parkride.events.PaymentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes {@link PaymentEvent} records to the {@code payment-events} Kafka topic.
 *
 * <p>Uses the bookingId (UUID string) as the Kafka message key, so all events
 * for the same booking land on the same partition — preserving ordering for
 * downstream consumers (e.g., Notification Service receiving CHARGED then REFUNDED).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentEventPublisher {

    @Value("${spring.kafka.topics.payment-events}")
    private String paymentEventsTopic;

    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;

    public void publish(PaymentEvent event) {
        String key = event.getReferenceId() != null
                ? event.getReferenceId().toString()
                : event.getUserId().toString();

        CompletableFuture<SendResult<String, PaymentEvent>> future =
                kafkaTemplate.send(paymentEventsTopic, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish PaymentEvent [type={}, key={}]: {}",
                        event.getEventType(), key, ex.getMessage());
            } else {
                log.debug("Published PaymentEvent [type={}, key={}, partition={}, offset={}]",
                        event.getEventType(), key,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}
