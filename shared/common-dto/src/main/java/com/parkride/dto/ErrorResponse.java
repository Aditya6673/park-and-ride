package com.parkride.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

/**
 * Structured error response modelled on RFC 7807 — Problem Details for HTTP APIs.
 *
 * <p>Every {@code GlobalExceptionHandler} across all services must return this
 * type (wrapped in the standard HTTP body — not inside {@link ApiResponse}).
 * Using a dedicated error type rather than reusing ApiResponse prevents
 * accidental success/failure ambiguity and gives consumers a strongly-typed
 * error contract to parse against.
 *
 * <p>JSON shape:
 * <pre>
 * {
 *   "status":    409,
 *   "error":     "CONFLICT",
 *   "message":   "Parking slot A-12 is no longer available",
 *   "path":      "/api/v1/bookings",
 *   "timestamp": "2026-04-14T10:05:30Z",
 *
 *   // Only present on validation errors (HTTP 400):
 *   "violations": [
 *     { "field": "startTime", "message": "must not be null" }
 *   ]
 * }
 * </pre>
 *
 * <p>Usage in {@code GlobalExceptionHandler}:
 * <pre>
 * {@code
 * ErrorResponse error = ErrorResponse.builder()
 *     .status(409)
 *     .error("CONFLICT")
 *     .message(ex.getMessage())
 *     .path(request.getRequestURI())
 *     .build();
 *
 * return ResponseEntity.status(409).body(error);
 * }
 * </pre>
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ErrorResponse {

    /** HTTP status code (mirrors the response status line). */
    @JsonProperty("status")
    private final int status;

    /**
     * Short, machine-readable error category.
     * Use HTTP reason phrases: "BAD_REQUEST", "UNAUTHORIZED", "CONFLICT", etc.
     * Frontend code should branch on this field, not on {@code message}.
     */
    @JsonProperty("error")
    private final String error;

    /**
     * Human-readable description. Safe to display in UI after sanitisation.
     * Must NOT contain stack traces, SQL, or internal class names.
     */
    @JsonProperty("message")
    private final String message;

    /**
     * The request URI that triggered the error.
     * Populated from {@code HttpServletRequest.getRequestURI()}.
     */
    @JsonProperty("path")
    private final String path;

    @JsonProperty("timestamp")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    @Builder.Default
    private final Instant timestamp = Instant.now();

    /**
     * Field-level validation violations. Only present on HTTP 400 responses
     * triggered by {@code @Valid} / {@code MethodArgumentNotValidException}.
     * Absent (null → omitted by Jackson) for all other error types.
     */
    @JsonProperty("violations")
    private final List<FieldViolation> violations;

    // ── Nested type ───────────────────────────────────────────────────────

    /**
     * Single field-level constraint violation — one entry per invalid field.
     *
     * <pre>
     * { "field": "vehicleNumber", "message": "must match '[A-Z]{2}[0-9]{2}[A-Z]{2}[0-9]{4}'" }
     * </pre>
     */
    @Getter
    @Builder
    public static final class FieldViolation {

        /** The request body field that failed validation. */
        @JsonProperty("field")
        private final String field;

        /** The constraint message (from the annotation's {@code message} attribute). */
        @JsonProperty("message")
        private final String message;
    }
}