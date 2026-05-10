package com.parkride.ride.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Centralized RFC 9457 problem+json error responses for the Ride Service.
 */
@RestControllerAdvice
@Slf4j
@SuppressWarnings("null") // Spring ProblemDetail API lacks @NonNull on forStatusAndDetail()/setType()
public class GlobalExceptionHandler {

    // ── 404 ────────────────────────────────────────────────────────────────

    @ExceptionHandler(RideNotFoundException.class)
    ProblemDetail handleRideNotFound(RideNotFoundException ex, WebRequest req) {
        return problem(HttpStatus.NOT_FOUND, "ride-not-found", ex.getMessage(), req);
    }

    @ExceptionHandler(DriverNotFoundException.class)
    ProblemDetail handleDriverNotFound(DriverNotFoundException ex, WebRequest req) {
        return problem(HttpStatus.NOT_FOUND, "driver-not-found", ex.getMessage(), req);
    }

    // ── 409 ────────────────────────────────────────────────────────────────

    @ExceptionHandler(RideNotCancellableException.class)
    ProblemDetail handleNotCancellable(RideNotCancellableException ex, WebRequest req) {
        return problem(HttpStatus.CONFLICT, "ride-not-cancellable", ex.getMessage(), req);
    }

    // ── 403 ────────────────────────────────────────────────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex, WebRequest req) {
        return problem(HttpStatus.FORBIDDEN, "access-denied", ex.getMessage(), req);
    }

    // ── 409 — Illegal state ────────────────────────────────────────────────

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleIllegalState(IllegalStateException ex, WebRequest req) {
        return problem(HttpStatus.CONFLICT, "invalid-state", ex.getMessage(), req);
    }

    // ── 503 ────────────────────────────────────────────────────────────────

    @ExceptionHandler(NoDriverAvailableException.class)
    ProblemDetail handleNoDriver(NoDriverAvailableException ex, WebRequest req) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "no-driver-available", ex.getMessage(), req);
    }

    // ── 400 — Validation ───────────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex, WebRequest req) {
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField,
                        fe -> fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage(),
                        (a, b) -> a));
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "validation-error",
                "Request validation failed", req);
        pd.setProperty("errors", errors);
        return pd;
    }

    // ── 500 — Catch-all ────────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    ProblemDetail handleGeneric(Exception ex, WebRequest req) {
        log.error("Unhandled exception in ride-service", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "An unexpected error occurred", req);
    }

    // ── Private helper ─────────────────────────────────────────────────────

    private ProblemDetail problem(HttpStatus status, String type, String detail, WebRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("https://parkride.com/errors/" + type));
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("path", req.getDescription(false).replace("uri=", ""));
        return pd;
    }
}
