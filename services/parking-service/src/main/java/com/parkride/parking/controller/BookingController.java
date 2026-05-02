package com.parkride.parking.controller;

import com.parkride.dto.ApiResponse;
import com.parkride.parking.dto.BookingResponse;
import com.parkride.parking.dto.CreateBookingRequest;
import com.parkride.parking.service.BookingService;
import com.parkride.parking.service.QRCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Booking management endpoints — all require a valid JWT.
 *
 * <p>The authenticated user's UUID is extracted from the JWT principal
 * (set by {@link com.parkride.parking.security.JwtAuthFilter}) and passed
 * directly to the service layer without a DB lookup.
 */
@Tag(name = "Bookings", description = "Create, view, cancel bookings and retrieve QR codes")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;
    private final QRCodeService  qrCodeService;

    @Operation(summary = "Create a new parking booking")
    @PostMapping
    public ResponseEntity<ApiResponse<BookingResponse>> createBooking(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody CreateBookingRequest request) {

        BookingResponse booking = bookingService.createBooking(UUID.fromString(userId), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Booking confirmed", booking));
    }

    @Operation(summary = "Get all bookings for the current user (paginated)")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<BookingResponse>>> getMyBookings(
            @AuthenticationPrincipal String userId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {

        Page<BookingResponse> bookings = bookingService.getUserBookings(
                UUID.fromString(userId),
                PageRequest.of(page, size, Sort.by("createdAt").descending()));

        return ResponseEntity.ok(ApiResponse.success("Bookings fetched", bookings));
    }

    @Operation(summary = "Get a specific booking by ID")
    @GetMapping("/{bookingId}")
    public ResponseEntity<ApiResponse<BookingResponse>> getBooking(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID bookingId) {

        BookingResponse booking = bookingService.getBooking(bookingId, UUID.fromString(userId));
        return ResponseEntity.ok(ApiResponse.success("Booking fetched", booking));
    }

    @Operation(summary = "Cancel a booking")
    @DeleteMapping("/{bookingId}")
    public ResponseEntity<ApiResponse<Void>> cancelBooking(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID bookingId) {

        bookingService.cancelBooking(bookingId, UUID.fromString(userId));
        return ResponseEntity.ok(ApiResponse.success("Booking cancelled successfully"));
    }

    @Operation(summary = "Get QR code token (JWT string) for a booking")
    @GetMapping("/{bookingId}/qr-token")
    public ResponseEntity<ApiResponse<String>> getQrToken(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID bookingId) {

        String token = bookingService.getQrToken(bookingId, UUID.fromString(userId));
        return ResponseEntity.ok(ApiResponse.success("QR token fetched", token));
    }

    @Operation(summary = "Get QR code as a PNG image (400×400)")
    @GetMapping(value = "/{bookingId}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getQrImage(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID bookingId) {

        String token = bookingService.getQrToken(bookingId, UUID.fromString(userId));
        byte[] imageBytes = qrCodeService.generateQrImage(token);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(imageBytes);
    }
}
