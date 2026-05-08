package com.parkride.notification.service;

import com.twilio.Twilio;
import com.twilio.exception.ApiException;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * SMS delivery service backed by the Twilio REST API.
 *
 * <p><strong>Enabled mode:</strong> when {@code twilio.account-sid} is configured,
 * the service initialises the Twilio SDK on startup and sends real SMS messages.
 *
 * <p><strong>Disabled mode:</strong> when {@code twilio.account-sid} is blank (default),
 * the service logs a debug message and silently skips delivery — safe for local
 * development without a Twilio account.
 *
 * <p>All SMS failures are caught and logged at WARN level. They do NOT propagate
 * to the caller — SMS is best-effort alongside the primary email notification.
 *
 * <h3>To enable Twilio:</h3>
 * Set the following environment variables before starting notification-service:
 * <pre>
 *   TWILIO_ACCOUNT_SID=ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
 *   TWILIO_AUTH_TOKEN=your_auth_token
 *   TWILIO_FROM_NUMBER=+1234567890   # your Twilio phone number (E.164)
 * </pre>
 */
@Slf4j
@Service
public class SmsService {

    @Value("${twilio.account-sid:}")
    private String accountSid;

    @Value("${twilio.auth-token:}")
    private String authToken;

    @Value("${twilio.from-number:}")
    private String fromNumber;

    /** Whether Twilio credentials are configured and SDK is initialised. */
    private boolean twilioEnabled = false;

    @PostConstruct
    void init() {
        if (accountSid != null && !accountSid.isBlank()
                && authToken != null && !authToken.isBlank()) {
            Twilio.init(accountSid, authToken);
            twilioEnabled = true;
            log.info("Twilio SMS service initialised — from number: {}", fromNumber);
        } else {
            log.info("Twilio credentials not configured — SMS delivery disabled (log stub active)");
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Sends an SMS to the given phone number.
     *
     * <p>If Twilio is not configured, or the phone number is null/blank,
     * the call is a no-op (logged at DEBUG level).
     *
     * @param phoneNumber recipient number in E.164 format (e.g. {@code +919876543210})
     * @param body        plain-text message body (max 160 chars recommended)
     */
    public void send(String phoneNumber, String body) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            log.debug("SMS skipped — recipient phone number is not available");
            return;
        }

        if (!twilioEnabled) {
            log.info("[SMS STUB] To: {} | Body: {}", phoneNumber, body);
            return;
        }

        try {
            Message message = Message.creator(
                            new PhoneNumber(phoneNumber),
                            new PhoneNumber(fromNumber),
                            body)
                    .create();

            log.info("SMS sent — SID: {}, To: {}, Status: {}", message.getSid(), phoneNumber, message.getStatus());

        } catch (ApiException ex) {
            log.warn("Twilio API error sending SMS to {}: [{}] {}",
                    phoneNumber, ex.getCode(), ex.getMessage());
        } catch (Exception ex) {
            log.warn("Unexpected error sending SMS to {}: {}", phoneNumber, ex.getMessage(), ex);
        }
    }

    /**
     * Sends a booking confirmation SMS.
     *
     * @param phoneNumber recipient number
     * @param slotLabel   human-readable slot label, e.g. "B-12 / Ground Floor"
     */
    public void sendBookingConfirmation(String phoneNumber, String slotLabel) {
        send(phoneNumber,
                "✅ Park & Ride: Your slot " + slotLabel + " is confirmed! " +
                "Check your email for the QR code. Safe travels!");
    }

    /**
     * Sends a booking cancellation SMS.
     *
     * @param phoneNumber recipient number
     * @param slotLabel   slot label
     */
    public void sendBookingCancellation(String phoneNumber, String slotLabel) {
        send(phoneNumber,
                "❌ Park & Ride: Your booking for slot " + slotLabel +
                " has been cancelled. Refund will be credited to your wallet shortly.");
    }

    /**
     * Sends a payment confirmation SMS.
     *
     * @param phoneNumber recipient number
     * @param amount      amount charged
     */
    public void sendPaymentConfirmation(String phoneNumber, java.math.BigDecimal amount) {
        send(phoneNumber,
                "💳 Park & Ride: ₹" + amount + " charged from your wallet. " +
                "Your parking session is active. Have a great day!");
    }

    /**
     * Sends an OTP SMS.
     *
     * @param phoneNumber recipient number
     * @param otp         6-digit OTP code
     */
    public void sendOtp(String phoneNumber, String otp) {
        send(phoneNumber,
                "Your Park & Ride OTP is: " + otp + ". Valid for 5 minutes. " +
                "Do not share with anyone.");
    }
}
