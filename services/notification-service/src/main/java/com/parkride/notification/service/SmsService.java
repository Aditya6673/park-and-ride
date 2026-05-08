package com.parkride.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * SMS delivery stub — logs the notification instead of making real API calls.
 *
 * <p>Phase A placeholder. To enable real SMS in Phase B/C:
 * <ol>
 *   <li>Add Twilio SDK dependency to pom.xml</li>
 *   <li>Inject {@code @Value("${twilio.account-sid}")} + {@code auth-token} + {@code from-number}</li>
 *   <li>Replace the log statement with {@code Message.creator(...).create()}</li>
 * </ol>
 */
@Slf4j
@Service
public class SmsService {

    /**
     * Sends (or stubs) an SMS to the given phone number.
     *
     * @param phoneNumber recipient phone number in E.164 format (e.g. {@code +919876543210})
     * @param message     plain-text SMS body (max 160 chars recommended)
     */
    public void send(String phoneNumber, String message) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            log.debug("SMS skipped — no phone number available");
            return;
        }
        // TODO Phase B: replace with Twilio API call
        log.info("[SMS STUB] To: {} | Message: {}", phoneNumber, message);
    }

    /**
     * Sends (or stubs) an OTP SMS.
     *
     * @param phoneNumber recipient number
     * @param otp         6-digit OTP code
     */
    public void sendOtp(String phoneNumber, String otp) {
        send(phoneNumber, "Your Park & Ride OTP is: " + otp + ". Valid for 5 minutes.");
    }
}
