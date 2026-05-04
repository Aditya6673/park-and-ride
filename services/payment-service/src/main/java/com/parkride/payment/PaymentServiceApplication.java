package com.parkride.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Payment Service — wallet management and idempotent payment processing.
 *
 * <p>Listens to {@code booking-events} Kafka topic and:
 * <ul>
 *   <li>BOOKING_CONFIRMED → debits the user's wallet</li>
 *   <li>BOOKING_CANCELLED → credits the user's wallet (refund)</li>
 * </ul>
 * Publishes {@code payment-events} for the Notification Service.
 */
@SpringBootApplication
@EnableAsync
public class PaymentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
