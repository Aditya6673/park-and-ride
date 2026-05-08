package com.parkride.notification.service;

import com.parkride.events.BookingEvent;
import com.parkride.events.PaymentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Routes incoming Kafka events to the correct notification channels.
 *
 * <p>This service is the single decision point for:
 * <ul>
 *   <li>Which template to render for each event type</li>
 *   <li>What subject line to use</li>
 *   <li>Whether to send email, SMS, or both</li>
 * </ul>
 *
 * <p>Delivery is delegated to {@link EmailService} and {@link SmsService}.
 * Both are best-effort — failures are caught and logged internally,
 * so this method never throws.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a")
                    .withZone(ZoneId.of("Asia/Kolkata"));

    private final EmailService emailService;
    private final SmsService   smsService;

    // ── BookingEvent dispatching ───────────────────────────────────────────────

    public void dispatch(BookingEvent event) {
        String email     = event.getUserEmail();
        String phone     = event.getUserPhone();
        String userName  = event.getUserName() != null ? event.getUserName() : "Valued Customer";
        String slotLabel = event.getSlotLabel() != null ? event.getSlotLabel() : "your slot";

        switch (event.getEventType()) {
            case BOOKING_CONFIRMED -> {
                Map<String, Object> vars = new HashMap<>();
                vars.put("userName",    userName);
                vars.put("bookingId",   event.getBookingId().toString());
                vars.put("slotLabel",   slotLabel);
                vars.put("startTime",   DATE_FMT.format(event.getStartTime()));
                vars.put("endTime",     DATE_FMT.format(event.getEndTime()));
                vars.put("amount",      event.getAmount());
                vars.put("qrCodeToken", event.getQrCodeToken());

                emailService.sendHtml(email,
                        "✅ Parking Confirmed — " + slotLabel,
                        "booking-confirmed", vars);

                smsService.sendBookingConfirmation(phone, slotLabel);
            }
            case BOOKING_CANCELLED -> {
                Map<String, Object> vars = new HashMap<>();
                vars.put("userName",  userName);
                vars.put("bookingId", event.getBookingId().toString());
                vars.put("slotLabel", slotLabel);
                vars.put("amount",    event.getAmount());

                emailService.sendHtml(email,
                        "❌ Booking Cancelled — Refund Initiated",
                        "booking-cancelled", vars);

                smsService.sendBookingCancellation(phone, slotLabel);
            }
            case BOOKING_NO_SHOW -> {
                Map<String, Object> vars = new HashMap<>();
                vars.put("userName",  userName);
                vars.put("bookingId", event.getBookingId().toString());
                vars.put("slotLabel", slotLabel);

                emailService.sendHtml(email,
                        "⚠️ Booking Marked as No-Show",
                        "booking-cancelled", vars);  // reuse cancelled template

                smsService.send(phone,
                        "⚠️ Park & Ride: Your booking for slot " + slotLabel +
                        " was marked no-show. Contact support if this is an error.");
            }
            case SLOT_CHECKED_IN ->
                log.debug("SLOT_CHECKED_IN for booking {} — no notification configured",
                        event.getBookingId());
            default ->
                log.debug("No notification handler for BookingEvent type: {}",
                        event.getEventType());
        }
    }

    // ── PaymentEvent dispatching ───────────────────────────────────────────────

    public void dispatch(PaymentEvent event) {
        String email    = event.getUserEmail();
        String phone    = event.getUserPhone();
        String userName = event.getUserName() != null ? event.getUserName() : "Valued Customer";

        switch (event.getEventType()) {
            case PAYMENT_CHARGED -> {
                Map<String, Object> vars = new HashMap<>();
                vars.put("userName",     userName);
                vars.put("amount",       event.getAmount());
                vars.put("balanceAfter", event.getBalanceAfter());
                vars.put("referenceId",  event.getReferenceId());

                emailService.sendHtml(email,
                        "💳 Payment of ₹" + event.getAmount() + " Received",
                        "payment-charged", vars);

                smsService.sendPaymentConfirmation(phone, event.getAmount());
            }
            case REFUND_PROCESSED -> {
                Map<String, Object> vars = new HashMap<>();
                vars.put("userName",     userName);
                vars.put("amount",       event.getAmount());
                vars.put("balanceAfter", event.getBalanceAfter());
                vars.put("referenceId",  event.getReferenceId());

                emailService.sendHtml(email,
                        "💰 Refund of ₹" + event.getAmount() + " Credited to Wallet",
                        "refund-processed", vars);

                smsService.send(phone,
                        "💰 Park & Ride: ₹" + event.getAmount() +
                        " refunded to your wallet. New balance: ₹" + event.getBalanceAfter());
            }
            case PAYMENT_FAILED -> {
                Map<String, Object> vars = new HashMap<>();
                vars.put("userName",      userName);
                vars.put("amount",        event.getAmount());
                vars.put("failureReason", event.getFailureReason());

                emailService.sendHtml(email,
                        "⚠️ Payment Failed — Action Required",
                        "payment-charged", vars);

                smsService.send(phone,
                        "⚠️ Park & Ride: Payment of ₹" + event.getAmount() +
                        " failed. Please top up your wallet and try again.");
            }
            default ->
                log.debug("No notification handler for PaymentEvent type: {}",
                        event.getEventType());
        }
    }
}
