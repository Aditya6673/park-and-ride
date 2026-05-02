package com.parkride.parking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Park & Ride — Parking Service entry point.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Parking lot and slot management (CRUD)</li>
 *   <li>Slot booking with distributed locking (Redisson)</li>
 *   <li>Real-time availability broadcast via WebSocket (STOMP)</li>
 *   <li>QR code generation (ZXing + signed JWT)</li>
 *   <li>Kafka event publishing (BookingEvent)</li>
 *   <li>Scheduled no-show auto-cancellation</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
public class ParkingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ParkingServiceApplication.class, args);
    }
}
