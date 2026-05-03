package com.parkride.parking.controller;

import com.parkride.dto.ApiResponse;
import com.parkride.parking.dto.ParkingLotResponse;
import com.parkride.parking.dto.ParkingSlotResponse;
import com.parkride.parking.repository.ParkingLotRepository;
import com.parkride.parking.repository.ParkingSlotRepository;
import com.parkride.parking.service.AvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Parking Lots", description = "Search and browse parking lots and their slots")
@RestController
@RequestMapping("/api/v1/parking/lots")
@RequiredArgsConstructor
// "null" — Eclipse @NonNull false positive on Hibernate-populated entity IDs inside Optional.map().
@SuppressWarnings("null")
public class ParkingLotController {

    private final ParkingLotRepository  lotRepository;
    private final ParkingSlotRepository slotRepository;
    private final AvailabilityService   availabilityService;

    @Operation(summary = "List active lots by city (paginated)")
    @GetMapping
    public ResponseEntity<ApiResponse<List<ParkingLotResponse>>> listByCity(
            @RequestParam String city,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        List<ParkingLotResponse> lots = lotRepository
                .findByCityIgnoreCaseAndActiveTrue(city, PageRequest.of(page, size))
                .stream()
                .map(lot -> ParkingLotResponse.from(lot, availabilityService.getAvailableCount(lot.getId())))
                .toList();

        return ResponseEntity.ok(ApiResponse.success("Lots fetched", lots));
    }

    @Operation(summary = "Find lots near a coordinate (Haversine radius search)")
    @GetMapping("/nearby")
    public ResponseEntity<ApiResponse<List<ParkingLotResponse>>> findNearby(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "5.0") double radiusKm) {

        List<ParkingLotResponse> lots = lotRepository.findNearby(lat, lng, radiusKm).stream()
                .map(lot -> ParkingLotResponse.from(lot, availabilityService.getAvailableCount(lot.getId())))
                .toList();

        return ResponseEntity.ok(ApiResponse.success("Nearby lots fetched", lots));
    }

    @Operation(summary = "Get lot details with real-time available slot count")
    @GetMapping("/{lotId}")
    public ResponseEntity<ApiResponse<ParkingLotResponse>> getLot(@PathVariable UUID lotId) {
        return lotRepository.findById(lotId)
                .map(lot -> ResponseEntity.ok(ApiResponse.success(
                        "Lot fetched",
                        ParkingLotResponse.from(lot, availabilityService.getAvailableCount(lot.getId())))))
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "List all slots in a lot")
    @GetMapping("/{lotId}/slots")
    public ResponseEntity<ApiResponse<List<ParkingSlotResponse>>> getSlots(@PathVariable UUID lotId) {
        List<ParkingSlotResponse> slots = slotRepository.findByLotIdAndActiveTrue(lotId).stream()
                .map(ParkingSlotResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success("Slots fetched", slots));
    }
}
