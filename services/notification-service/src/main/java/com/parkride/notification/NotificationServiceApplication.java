package com.parkride.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Notification Service — stateless Kafka consumer.
 *
 * <p>Listens to {@code booking-events} and {@code payment-events} topics,
 * and dispatches email (and stubbed SMS) notifications for every relevant
 * booking and payment lifecycle transition.
 *
 * <p>No database — no {@code @EnableJpaRepositories}, no Flyway.
 * All state lives in the Kafka event stream itself.
 */
@SpringBootApplication
public class NotificationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
