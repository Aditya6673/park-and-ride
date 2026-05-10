package com.parkride.ride.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CompleteRideRequest(

        @NotNull
        @DecimalMin(value = "0.0")
        BigDecimal finalFare,

        @NotNull
        @Positive
        Double distanceKm
) {}
