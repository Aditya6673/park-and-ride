package com.parkride.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Kafka event published by the Ride Service for every ride lifecycle transition.
 *
 * <p>Topic: {@code ride-events}
 * <p>Consumed by: Notification Service, Payment Service, Analytics Service.
 *
 * <p>Geolocation fields use decimal degrees (WGS84).
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RideEvent {

    // ── Event metadata ────────────────────────────────────────────────────────

    /** Unique ID for this event — used for consumer idempotency checks. */
    @JsonProperty("eventId")
    private UUID eventId;

    /** Type of ride transition that triggered this event. */
    @JsonProperty("eventType")
    private EventType eventType;

    /** Wall-clock time when the Ride Service emitted this event. */
    @JsonProperty("occurredAt")
    private Instant occurredAt;

    // ── Ride payload ──────────────────────────────────────────────────────────

    /** The ride booking that changed state. */
    @JsonProperty("rideId")
    private UUID rideId;

    /** The passenger who requested the ride. */
    @JsonProperty("userId")
    private UUID userId;

    /**
     * The assigned driver. Null until a driver accepts the booking
     * (i.e., null in RIDE_REQUESTED events).
     */
    @JsonProperty("driverId")
    private UUID driverId;

    /** The vehicle assigned for this ride. Null until driver is assigned. */
    @JsonProperty("vehicleId")
    private UUID vehicleId;

    /**
     * Type of vehicle: {@code CAB}, {@code SHUTTLE}, or {@code ERICKSHAW}.
     * Determines the fare structure applied by the Pricing Service.
     */
    @JsonProperty("vehicleType")
    private String vehicleType;

    /** Ride status after the transition. */
    @JsonProperty("status")
    private String status;

    // ── Location ──────────────────────────────────────────────────────────────

    /** Pickup latitude (WGS84 decimal degrees). */
    @JsonProperty("pickupLat")
    private Double pickupLat;

    /** Pickup longitude (WGS84 decimal degrees). */
    @JsonProperty("pickupLng")
    private Double pickupLng;

    /** Drop-off latitude. */
    @JsonProperty("dropoffLat")
    private Double dropoffLat;

    /** Drop-off longitude. */
    @JsonProperty("dropoffLng")
    private Double dropoffLng;

    // ── Fare ─────────────────────────────────────────────────────────────────

    /**
     * Estimated or final fare.
     * Estimated in RIDE_REQUESTED / RIDE_CONFIRMED events.
     * Final in RIDE_COMPLETED.
     * Null in RIDE_CANCELLED (no charge if cancelled before pickup).
     */
    @JsonProperty("fare")
    private BigDecimal fare;

    /**
     * Distance travelled in kilometres.
     * Null until the ride is COMPLETED.
     */
    @JsonProperty("distanceKm")
    private Double distanceKm;

    /** Whether this ride is part of a pool group (shared with other passengers). */
    @JsonProperty("isPooled")
    private Boolean isPooled;

    /** Pool group ID if {@link #isPooled} is true. Null for solo rides. */
    @JsonProperty("poolGroupId")
    private UUID poolGroupId;

    // ── Event type discriminator ──────────────────────────────────────────────

    public enum EventType {

        /** Passenger requested a ride — driver not yet assigned. */
        RIDE_REQUESTED,

        /** Driver accepted and is en route to pickup. */
        RIDE_CONFIRMED,

        /** Driver arrived at pickup location. */
        DRIVER_ARRIVED,

        /** Ride started — passenger on board. */
        RIDE_STARTED,

        /** Ride ended at drop-off. Triggers final payment charge. */
        RIDE_COMPLETED,

        /** Ride cancelled (by passenger or system). */
        RIDE_CANCELLED,

        /** Two or more ride requests matched into a pool group. */
        RIDE_POOLED
    }
}
