package com.parkride.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.Instant;

/**
 * Universal API response envelope for every endpoint across all services.
 *
 * <p>All controllers return {@code ApiResponse<T>} — never raw domain objects
 * or Spring's {@code ResponseEntity} directly. This contract guarantees that
 * every HTTP response from the platform has a consistent shape, making it
 * trivial for the frontend and any third-party consumers to parse responses
 * without branching on response structure.
 *
 * <p>Shape:
 * <pre>
 * // Success
 * {
 *   "success": true,
 *   "message": "Booking confirmed",
 *   "data":    { ... },          // present on success
 *   "timestamp": "2026-04-14T10:00:00Z"
 * }
 *
 * // Failure  (data field is omitted — never null in the JSON)
 * {
 *   "success": false,
 *   "message": "Slot no longer available",
 *   "timestamp": "2026-04-14T10:00:01Z"
 * }
 * </pre>
 *
 * <p>Usage in a controller:
 * <pre>
 * {@code
 * return ResponseEntity.ok(ApiResponse.success("Booking confirmed", bookingDto));
 * return ResponseEntity.status(409).body(ApiResponse.failure("Slot no longer available"));
 * }
 * </pre>
 *
 * @param <T> payload type — must be serialisable by Jackson
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)   // omit 'data' field entirely when null
public final class ApiResponse<T> {

    @JsonProperty("success")
    private final boolean success;

    @JsonProperty("message")
    private final String message;

    /**
     * The response payload. Absent from the JSON when {@code null}
     * (controlled by {@code @JsonInclude(NON_NULL)} above).
     */
    @JsonProperty("data")
    private final T data;

    @JsonProperty("timestamp")
    private final Instant timestamp;

    // ── Private constructor — use static factories below ──────────────────

    private ApiResponse(boolean success, String message, T data) {
        this.success   = success;
        this.message   = message;
        this.data      = data;
        this.timestamp = Instant.now();
    }

    // ── Static factories ──────────────────────────────────────────────────

    /**
     * Successful response with a payload and a human-readable message.
     *
     * @param message short description of the outcome (e.g. "Booking confirmed")
     * @param data    the response body — never {@code null} for success responses
     * @param <T>     payload type
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    /**
     * Successful response without a payload body (e.g. DELETE, logout).
     *
     * @param message short description (e.g. "Slot released successfully")
     */
    public static <T> ApiResponse<T> success(String message) {
        return new ApiResponse<>(true, message, null);
    }

    /**
     * Failure response. The {@code data} field is omitted from the JSON.
     * For structured error details use {@link ErrorResponse} instead.
     *
     * @param message human-readable failure reason
     */
    public static <T> ApiResponse<T> failure(String message) {
        return new ApiResponse<>(false, message, null);
    }
}