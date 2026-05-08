package com.parkride.notification.service;

import com.parkride.events.BookingEvent;
import com.parkride.events.PaymentEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationDispatchService — unit tests")
class NotificationDispatchServiceTest {

    @Mock EmailService emailService;
    @Mock SmsService   smsService;

    @InjectMocks NotificationDispatchService dispatchService;

    private UUID bookingId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        bookingId = UUID.randomUUID();
        userId    = UUID.randomUUID();
    }

    // ── BookingEvent routing ───────────────────────────────────────────────

    @Test
    @DisplayName("BOOKING_CONFIRMED → sends email with booking-confirmed template")
    void dispatch_bookingConfirmed_sendsConfirmationEmail() {
        BookingEvent event = bookingEvent(BookingEvent.EventType.BOOKING_CONFIRMED,
                new BigDecimal("150.00"), "test@parkride.com");

        dispatchService.dispatch(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailService).sendHtml(
                eq("test@parkride.com"),
                contains("Confirmed"),
                eq("booking-confirmed"),
                varsCaptor.capture()
        );

        Map<String, Object> vars = varsCaptor.getValue();
        assertThat(vars).containsKey("bookingId");
        assertThat(vars).containsKey("slotLabel");
        assertThat(vars).containsKey("startTime");
        assertThat(vars).containsKey("amount");
        assertThat(vars.get("userName")).isEqualTo("Aditya");
    }

    @Test
    @DisplayName("BOOKING_CONFIRMED → also triggers SMS (phone may be null — stubbed)")
    void dispatch_bookingConfirmed_triggersSmsStub() {
        BookingEvent event = bookingEvent(BookingEvent.EventType.BOOKING_CONFIRMED,
                new BigDecimal("120.00"), "user@example.com");

        dispatchService.dispatch(event);

        verify(smsService).sendBookingConfirmation(isNull(), anyString());
    }

    @Test
    @DisplayName("BOOKING_CANCELLED → sends email with booking-cancelled template")
    void dispatch_bookingCancelled_sendsCancellationEmail() {
        BookingEvent event = bookingEvent(BookingEvent.EventType.BOOKING_CANCELLED,
                new BigDecimal("120.00"), "user@example.com");

        dispatchService.dispatch(event);

        verify(emailService).sendHtml(
                eq("user@example.com"),
                contains("Cancelled"),
                eq("booking-cancelled"),
                any()
        );
        verify(smsService).sendBookingCancellation(isNull(), anyString());
    }

    @Test
    @DisplayName("BOOKING_NO_SHOW → reuses booking-cancelled template")
    void dispatch_noShow_reusesCancelledTemplate() {
        BookingEvent event = bookingEvent(BookingEvent.EventType.BOOKING_NO_SHOW,
                null, "user@example.com");

        dispatchService.dispatch(event);

        verify(emailService).sendHtml(
                eq("user@example.com"),
                contains("No-Show"),
                eq("booking-cancelled"),  // intentionally reused
                any()
        );
    }

    @Test
    @DisplayName("SLOT_CHECKED_IN → no email or SMS (no template configured)")
    void dispatch_checkedIn_noNotification() {
        BookingEvent event = bookingEvent(BookingEvent.EventType.SLOT_CHECKED_IN,
                null, "user@example.com");

        dispatchService.dispatch(event);

        verifyNoInteractions(emailService, smsService);
    }

    @Test
    @DisplayName("null userName → falls back to 'Valued Customer' in template vars")
    void dispatch_nullUserName_fallsBackToDefault() {
        BookingEvent event = BookingEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(BookingEvent.EventType.BOOKING_CONFIRMED)
                .bookingId(bookingId)
                .userId(userId)
                .userEmail("user@example.com")
                .userName(null)  // null name
                .slotLabel("A-01 / Floor 1")
                .amount(new BigDecimal("80.00"))
                .startTime(Instant.now().plusSeconds(3600))
                .endTime(Instant.now().plusSeconds(7200))
                .occurredAt(Instant.now())
                .build();

        dispatchService.dispatch(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(emailService).sendHtml(any(), any(), any(), cap.capture());
        assertThat(cap.getValue().get("userName")).isEqualTo("Valued Customer");
    }

    // ── PaymentEvent routing ───────────────────────────────────────────────

    @Test
    @DisplayName("PAYMENT_CHARGED → sends payment-charged template email")
    void dispatch_paymentCharged_sendsReceiptEmail() {
        PaymentEvent event = paymentEvent(PaymentEvent.EventType.PAYMENT_CHARGED,
                new BigDecimal("200.00"), "user@example.com");

        dispatchService.dispatch(event);

        verify(emailService).sendHtml(
                eq("user@example.com"),
                contains("Payment"),
                eq("payment-charged"),
                argThat(vars -> vars.containsKey("balanceAfter"))
        );
    }

    @Test
    @DisplayName("REFUND_PROCESSED → sends refund-processed template email")
    void dispatch_refundProcessed_sendsRefundEmail() {
        PaymentEvent event = paymentEvent(PaymentEvent.EventType.REFUND_PROCESSED,
                new BigDecimal("150.00"), "user@example.com");

        dispatchService.dispatch(event);

        verify(emailService).sendHtml(
                eq("user@example.com"),
                contains("Refund"),
                eq("refund-processed"),
                any()
        );
    }

    @Test
    @DisplayName("WALLET_TOPUP → no email (no template configured)")
    void dispatch_walletTopup_noNotification() {
        PaymentEvent event = paymentEvent(PaymentEvent.EventType.WALLET_TOPUP,
                new BigDecimal("500.00"), "user@example.com");

        dispatchService.dispatch(event);

        verifyNoInteractions(emailService, smsService);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private BookingEvent bookingEvent(BookingEvent.EventType type,
                                      java.math.BigDecimal amount,
                                      String email) {
        return BookingEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(type)
                .bookingId(bookingId)
                .userId(userId)
                .userEmail(email)
                .userName("Aditya")
                .slotLabel("B-12 / Ground Floor")
                .amount(amount)
                .startTime(Instant.now().plusSeconds(3600))
                .endTime(Instant.now().plusSeconds(7200))
                .occurredAt(Instant.now())
                .build();
    }

    private PaymentEvent paymentEvent(PaymentEvent.EventType type,
                                      BigDecimal amount,
                                      String email) {
        return PaymentEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(type)
                .userId(userId)
                .walletId(UUID.randomUUID())
                .amount(amount)
                .balanceAfter(new BigDecimal("800.00"))
                .referenceId(bookingId)
                .referenceType("BOOKING")
                .status("SUCCESS")
                .userEmail(email)
                .userName("Aditya")
                .occurredAt(Instant.now())
                .build();
    }
}
