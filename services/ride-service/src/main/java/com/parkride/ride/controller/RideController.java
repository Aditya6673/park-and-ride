package com.parkride.ride.controller;

import com.parkride.dto.ApiResponse;
import com.parkride.ride.dto.*;
import com.parkride.ride.service.RideService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rides")
@RequiredArgsConstructor
@Tag(name = "Rides", description = "Last-mile ride booking and lifecycle management")
public class RideController {

    private final RideService rideService;

    @PostMapping
    @Operation(summary = "Request a ride")
    public ResponseEntity<ApiResponse<RideResponse>> requestRide(
            @Valid @RequestBody RequestRideRequest req,
            @AuthenticationPrincipal String userId) {

        RideResponse ride = rideService.requestRide(UUID.fromString(userId), req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Ride requested", ride));
    }

    @GetMapping("/{rideId}")
    @Operation(summary = "Get ride details")
    public ApiResponse<RideResponse> getRide(
            @PathVariable UUID rideId,
            @AuthenticationPrincipal String userId) {

        return ApiResponse.success("Ride found", rideService.getById(rideId, UUID.fromString(userId)));
    }

    @GetMapping("/my")
    @Operation(summary = "List my rides (paginated)")
    public ApiResponse<Page<RideResponse>> myRides(
            @AuthenticationPrincipal String userId,
            @PageableDefault(size = 20, sort = "requestedAt") Pageable pageable) {

        return ApiResponse.success("Rides retrieved",
                rideService.listMyRides(UUID.fromString(userId), pageable));
    }

    @PostMapping("/{rideId}/cancel")
    @Operation(summary = "Cancel a ride")
    public ApiResponse<RideResponse> cancelRide(
            @PathVariable UUID rideId,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal String userId) {

        return ApiResponse.success("Ride cancelled",
                rideService.cancelRide(rideId, UUID.fromString(userId), reason));
    }

    @PostMapping("/{rideId}/rate")
    @Operation(summary = "Rate a completed ride (1–5)")
    public ApiResponse<RideResponse> rateRide(
            @PathVariable UUID rideId,
            @Valid @RequestBody RateRideRequest req,
            @AuthenticationPrincipal String userId) {

        return ApiResponse.success("Ride rated",
                rideService.rateRide(rideId, UUID.fromString(userId), req.rating()));
    }

    @PostMapping("/{rideId}/arrived")
    @Operation(summary = "Driver marks arrival at pickup")
    public ApiResponse<RideResponse> arrived(@PathVariable UUID rideId) {
        return ApiResponse.success("Driver arrived", rideService.markDriverArrived(rideId));
    }

    @PostMapping("/{rideId}/start")
    @Operation(summary = "Driver starts the ride")
    public ApiResponse<RideResponse> start(@PathVariable UUID rideId) {
        return ApiResponse.success("Ride started", rideService.startRide(rideId));
    }

    @PostMapping("/{rideId}/complete")
    @Operation(summary = "Driver completes the ride")
    public ApiResponse<RideResponse> complete(
            @PathVariable UUID rideId,
            @Valid @RequestBody CompleteRideRequest req) {

        return ApiResponse.success("Ride completed", rideService.completeRide(rideId, req));
    }
}
