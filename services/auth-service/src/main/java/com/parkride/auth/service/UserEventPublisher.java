package com.parkride.auth.service;

import com.parkride.events.UserEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes {@link UserEvent} records to the {@code user-events} Kafka topic.
 *
 * <p>Uses the {@code userId} (UUID string) as the Kafka message key so all events
 * for the same user land on the same partition — preserving ordering.
 *
 * <p>Failures are logged asynchronously but do <em>not</em> propagate to the caller:
 * a Kafka outage should not roll back the registration transaction.
 * The registration DB write always commits first; the event is best-effort.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserEventPublisher {

    @Value("${spring.kafka.topics.user-events:user-events}")
    private String userEventsTopic;

    private final KafkaTemplate<String, UserEvent> userEventKafkaTemplate;

    /**
     * Publishes the given event asynchronously.
     * Kafka send failures are logged but never rethrown.
     */
    @SuppressWarnings("null") // @Value-injected topic is non-null after context startup
    public void publish(UserEvent event) {
        String key = event.getUserId().toString();

        userEventKafkaTemplate.send(userEventsTopic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish UserEvent [type={}, userId={}]: {}",
                                event.getEventType(), event.getUserId(), ex.getMessage());
                    } else {
                        log.debug("Published UserEvent [type={}, userId={}, partition={}, offset={}]",
                                event.getEventType(), event.getUserId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
