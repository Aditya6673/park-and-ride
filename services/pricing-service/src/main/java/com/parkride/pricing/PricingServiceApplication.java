package com.parkride.pricing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Park &amp; Ride — Pricing Service
 *
 * <p>Dynamic surge pricing engine for parking lots.
 * <ul>
 *   <li>Computes fees: {@code base_rate × time_multiplier × occupancy_multiplier}</li>
 *   <li>Tracks real-time occupancy via Kafka {@code booking-events}</li>
 *   <li>Caches computed prices in Redis (30-second TTL)</li>
 * </ul>
 *
 * <p>Port: {@code 8085} · DB: {@code pricing_db}
 */
@SpringBootApplication
public class PricingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PricingServiceApplication.class, args);
    }
}
