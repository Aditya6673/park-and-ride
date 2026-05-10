package com.parkride.ride.controller;

import com.parkride.dto.ApiResponse;
import com.parkride.ride.domain.DriverStatus;
import com.parkride.ride.dto.*;
import com.parkride.ride.service.DriverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/drivers")
@RequiredArgsConstructor
@Tag(name = "Drivers", description = "Driver management and GPS location updates")
public class DriverController {

    private final DriverService driverService;

    @PostMapping
    @Operation(summary = "Register a new driver (Admin only)")
    public ResponseEntity<ApiResponse<DriverResponse>> register(
            @Valid @RequestBody RegisterDriverRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Driver registered", driverService.register(req)));
    }

    @GetMapping
    @Operation(summary = "List all drivers (Admin only)")
    public ApiResponse<Page<DriverResponse>> listAll(
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success("Drivers retrieved", driverService.listAll(pageable));
    }

    @GetMapping("/{driverId}")
    @Operation(summary = "Get driver by ID")
    public ApiResponse<DriverResponse> getById(@PathVariable UUID driverId) {
        return ApiResponse.success("Driver found", driverService.getById(driverId));
    }

    @PutMapping("/{driverId}/status")
    @Operation(summary = "Update driver availability (AVAILABLE / OFFLINE)")
    public ApiResponse<DriverResponse> updateStatus(
            @PathVariable UUID driverId,
            @RequestParam DriverStatus status) {
        return ApiResponse.success("Status updated", driverService.updateStatus(driverId, status));
    }

    @PutMapping("/{driverId}/location")
    @Operation(summary = "Update driver GPS location")
    public ApiResponse<DriverResponse> updateLocation(
            @PathVariable UUID driverId,
            @Valid @RequestBody UpdateDriverLocationRequest req) {
        return ApiResponse.success("Location updated", driverService.updateLocation(driverId, req));
    }
}
