package com.parkride.ride.service;

import com.parkride.ride.domain.Driver;
import com.parkride.ride.domain.DriverStatus;
import com.parkride.ride.dto.DriverResponse;
import com.parkride.ride.dto.RegisterDriverRequest;
import com.parkride.ride.dto.UpdateDriverLocationRequest;
import com.parkride.ride.exception.DriverNotFoundException;
import com.parkride.ride.repository.DriverRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@SuppressWarnings("null") // JPA API lacks @NonNull on save()/findById() returns
public class DriverService {

    private final DriverRepository driverRepository;

    @Transactional
    public DriverResponse register(RegisterDriverRequest req) {
        Driver driver = Driver.builder()
                .userId(req.userId())
                .name(req.name())
                .phone(req.phone())
                .licenseNumber(req.licenseNumber())
                .vehicleType(req.vehicleType())
                .vehiclePlate(req.vehiclePlate())
                .vehicleModel(req.vehicleModel())
                .status(DriverStatus.OFFLINE)
                .build();
        return DriverResponse.from(driverRepository.save(driver));
    }

    @Transactional(readOnly = true)
    public Page<DriverResponse> listAll(Pageable pageable) {
        return driverRepository.findAll(pageable).map(DriverResponse::from);
    }

    @Transactional(readOnly = true)
    public DriverResponse getById(UUID driverId) {
        return DriverResponse.from(findOrThrow(driverId));
    }

    @Transactional
    public DriverResponse updateStatus(UUID driverId, DriverStatus newStatus) {
        Driver driver = findOrThrow(driverId);
        switch (newStatus) {
            case AVAILABLE -> driver.goOnline();
            case OFFLINE   -> driver.goOffline();
            default -> throw new IllegalArgumentException("Cannot manually set status: " + newStatus);
        }
        return DriverResponse.from(driverRepository.save(driver));
    }

    @Transactional
    public DriverResponse updateLocation(UUID driverId, UpdateDriverLocationRequest req) {
        Driver driver = findOrThrow(driverId);
        driver.updateLocation(req.lat(), req.lng());
        return DriverResponse.from(driverRepository.save(driver));
    }

    // ── Package-private: used by RideService ────────────────────────────────

    Driver findOrThrow(UUID driverId) {
        return driverRepository.findById(driverId)
                .orElseThrow(() -> new DriverNotFoundException(driverId));
    }
}
