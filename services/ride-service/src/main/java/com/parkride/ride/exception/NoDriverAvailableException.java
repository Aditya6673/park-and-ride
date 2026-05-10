package com.parkride.ride.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class NoDriverAvailableException extends RuntimeException {
    public NoDriverAvailableException(String vehicleType) {
        super("No available " + vehicleType + " driver found nearby. Please try again shortly.");
    }
}
