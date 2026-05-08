package com.parkride.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Kafka event published by the Auth Service for user lifecycle transitions.
 *
 * <p>Topic: {@code user-events}
 * <p>Consumed by: Notification Service (welcome email, password-reset email).
 *
 * <p>Schema stability: fields may only be <em>added</em> — never removed or renamed —
 * once any consumer is deployed. New optional fields use {@code @JsonInclude(NON_NULL)}.
 *
 * <p>JSON shape:
 * <pre>
 * {
 *   "eventId":           "550e8400-...",
 *   "eventType":         "USER_REGISTERED",
 *   "userId":            "...",
 *   "occurredAt":        "2025-06-01T10:00:00Z",
 *   "email":             "user@example.com",
 *   "firstName":         "Aditya",
 *   "phone":             "+919876543210",
 *   "verificationToken": "abc123..."   // USER_REGISTERED only
 * }
 * </pre>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserEvent {

    /** Unique event identifier — used for deduplication by consumers. */
    private UUID eventId;

    /** The user this event concerns. */
    private UUID userId;

    /** Lifecycle transition that triggered this event. */
    private EventType eventType;

    /** Wall-clock time the event was emitted (UTC). */
    private Instant occurredAt;

    // ── User details (denormalised for consumer convenience) ──────────────────

    /** Primary email address — all transactional emails are sent here. */
    private String email;

    /** First name for personalised greeting in email templates. */
    private String firstName;

    /** Phone number for optional SMS delivery (may be {@code null}). */
    private String phone;

    // ── Token payloads ────────────────────────────────────────────────────────

    /**
     * Raw (un-hashed) email-verification token.
     * Only populated for {@link EventType#USER_REGISTERED}.
     * The Auth Service stores a SHA-256 hash; this value is embedded in the
     * verification URL sent to the user.
     */
    private String verificationToken;

    /**
     * Raw (un-hashed) password-reset token.
     * Only populated for {@link EventType#PASSWORD_RESET_REQUESTED}.
     * Expires after 1 hour (enforced by Auth Service on redemption).
     */
    private String resetToken;

    // ── Event type enum ───────────────────────────────────────────────────────

    public enum EventType {

        /**
         * A new user account was created successfully.
         * Notification Service should send a welcome email containing
         * a verification link built from {@link #verificationToken}.
         */
        USER_REGISTERED,

        /**
         * The user requested a password reset.
         * Notification Service should send a reset-link email built from
         * {@link #resetToken}. Token expires after 1 hour.
         */
        PASSWORD_RESET_REQUESTED
    }
}
