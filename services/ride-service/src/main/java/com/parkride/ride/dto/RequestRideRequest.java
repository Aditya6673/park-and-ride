package com.parkride.ride.dto;

import com.parkride.ride.domain.VehicleType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record RequestRideRequest(

        @NotNull(message = "vehicleType is required")
        VehicleType vehicleType,

        @NotNull(message = "pickupLat is required")
        @DecimalMin(value = "-90.0",  message = "pickupLat must be between -90 and 90")
        @DecimalMax(value = "90.0",   message = "pickupLat must be between -90 and 90")
        Double pickupLat,

        @NotNull(message = "pickupLng is required")
        @DecimalMin(value = "-180.0", message = "pickupLng must be between -180 and 180")
        @DecimalMax(value = "180.0",  message = "pickupLng must be between -180 and 180")
        Double pickupLng,

        String pickupAddress,

        @NotNull(message = "dropoffLat is required")
        @DecimalMin(value = "-90.0",  message = "dropoffLat must be between -90 and 90")
        @DecimalMax(value = "90.0",   message = "dropoffLat must be between -90 and 90")
        Double dropoffLat,

        @NotNull(message = "dropoffLng is required")
        @DecimalMin(value = "-180.0", message = "dropoffLng must be between -180 and 180")
        @DecimalMax(value = "180.0",  message = "dropoffLng must be between -180 and 180")
        Double dropoffLng,

        String dropoffAddress,

        /** Optional — links this ride to a parking booking. */
        UUID bookingId,

        /** True if the passenger is open to sharing the ride. */
        Boolean pooled
) {}
