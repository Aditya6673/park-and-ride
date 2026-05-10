package com.parkride.ride.dto;

import com.parkride.ride.domain.VehicleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record RegisterDriverRequest(

        @NotNull(message = "userId is required")
        java.util.UUID userId,

        @NotBlank(message = "name is required")
        String name,

        @NotBlank(message = "phone is required")
        @Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "phone must be in E.164 format")
        String phone,

        @NotBlank(message = "licenseNumber is required")
        String licenseNumber,

        @NotNull(message = "vehicleType is required")
        VehicleType vehicleType,

        @NotBlank(message = "vehiclePlate is required")
        String vehiclePlate,

        String vehicleModel
) {}
