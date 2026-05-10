package com.parkride.ride.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class DriverNotFoundException extends RuntimeException {
    public DriverNotFoundException(UUID driverId) {
        super("Driver not found: " + driverId);
    }
}
