package com.parkride.payment.service;

import com.parkride.events.BookingEvent;
import com.parkride.payment.domain.Transaction;
import com.parkride.payment.domain.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookingEventConsumer — unit tests")
@SuppressWarnings("null")
class BookingEventConsumerTest {

    @Mock WalletService         walletService;
    @Mock PaymentEventPublisher eventPublisher;
    @Mock Acknowledgment        ack;

    @InjectMocks BookingEventConsumer consumer;

    private UUID userId;
    private UUID bookingId;

    @BeforeEach
    void setUp() {
        userId    = UUID.randomUUID();
        bookingId = UUID.randomUUID();
    }

    // ── BOOKING_CONFIRMED → debit ─────────────────────────────────────────────

    @Test
    @DisplayName("BOOKING_CONFIRMED with amount — debits wallet and publishes PaymentEvent")
    void consume_confirmed_debitsWalletAndPublishes() {
        BookingEvent event = buildEvent(BookingEvent.EventType.BOOKING_CONFIRMED, new BigDecimal("120.00"));

        Transaction successTx = Transaction.builder()
                .id(UUID.randomUUID())
                .idempotencyKey(bookingId + ":BOOKING_CONFIRMED")
                .status(TransactionStatus.SUCCESS)
                .amount(new BigDecimal("120.00"))
                .wallet(com.parkride.payment.domain.Wallet.builder()
                        .id(UUID.randomUUID())
                        .balance(new BigDecimal("380.00"))
                        .build())
                .build();

        when(walletService.debit(eq(userId), eq(new BigDecimal("120.00")),
                eq(bookingId + ":" + BookingEvent.EventType.BOOKING_CONFIRMED.name()),
                eq(bookingId))).thenReturn(successTx);

        consumer.consume(event, 0, 0L, ack);

        verify(walletService).debit(eq(userId), eq(new BigDecimal("120.00")), anyString(), eq(bookingId));
        verify(eventPublisher).publish(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("BOOKING_CONFIRMED with no amount — skips debit and acknowledges")
    void consume_confirmed_noAmount_skipsDebit() {
        BookingEvent event = buildEvent(BookingEvent.EventType.BOOKING_CONFIRMED, null);

        consumer.consume(event, 0, 0L, ack);

        verify(walletService, never()).debit(any(), any(), any(), any());
        verify(eventPublisher, never()).publish(any());
        verify(ack).acknowledge();
    }

    // ── BOOKING_CANCELLED → credit ────────────────────────────────────────────

    @Test
    @DisplayName("BOOKING_CANCELLED with amount — credits wallet (refund) and publishes PaymentEvent")
    void consume_cancelled_creditsWalletAndPublishes() {
        BookingEvent event = buildEvent(BookingEvent.EventType.BOOKING_CANCELLED, new BigDecimal("120.00"));

        Transaction refundTx = Transaction.builder()
                .id(UUID.randomUUID())
                .idempotencyKey(bookingId + ":BOOKING_CANCELLED")
                .status(TransactionStatus.SUCCESS)
                .amount(new BigDecimal("120.00"))
                .wallet(com.parkride.payment.domain.Wallet.builder()
                        .id(UUID.randomUUID())
                        .balance(new BigDecimal("620.00"))
                        .build())
                .build();

        when(walletService.credit(eq(userId), eq(new BigDecimal("120.00")),
                eq(bookingId + ":" + BookingEvent.EventType.BOOKING_CANCELLED.name()),
                eq(bookingId))).thenReturn(refundTx);

        consumer.consume(event, 0, 0L, ack);

        verify(walletService).credit(eq(userId), eq(new BigDecimal("120.00")), anyString(), eq(bookingId));
        verify(eventPublisher).publish(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("BOOKING_NO_SHOW — treated same as BOOKING_CANCELLED (refund if applicable)")
    void consume_noShow_treatedAsCancelled() {
        BookingEvent event = buildEvent(BookingEvent.EventType.BOOKING_NO_SHOW, new BigDecimal("50.00"));

        Transaction refundTx = Transaction.builder()
                .id(UUID.randomUUID())
                .status(TransactionStatus.SUCCESS)
                .amount(new BigDecimal("50.00"))
                .wallet(com.parkride.payment.domain.Wallet.builder()
                        .id(UUID.randomUUID()).balance(BigDecimal.ZERO).build())
                .build();

        when(walletService.credit(any(), any(), any(), any())).thenReturn(refundTx);

        consumer.consume(event, 0, 0L, ack);

        verify(walletService).credit(eq(userId), eq(new BigDecimal("50.00")), anyString(), eq(bookingId));
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("SLOT_CHECKED_IN — ignored (no payment action), message acknowledged")
    void consume_checkedIn_ignored() {
        BookingEvent event = buildEvent(BookingEvent.EventType.SLOT_CHECKED_IN, null);

        consumer.consume(event, 0, 0L, ack);

        verifyNoInteractions(walletService, eventPublisher);
        verify(ack).acknowledge();
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Exception during processing — does NOT acknowledge (triggers Kafka retry)")
    void consume_exception_doesNotAcknowledge() {
        BookingEvent event = buildEvent(BookingEvent.EventType.BOOKING_CONFIRMED, new BigDecimal("100.00"));

        when(walletService.debit(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("DB connection lost"));

        consumer.consume(event, 0, 0L, ack);

        verify(ack, never()).acknowledge();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BookingEvent buildEvent(BookingEvent.EventType type, BigDecimal amount) {
        return BookingEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(type)
                .occurredAt(Instant.now())
                .bookingId(bookingId)
                .userId(userId)
                .slotId(UUID.randomUUID())
                .lotId(UUID.randomUUID())
                .status(type.name())
                .amount(amount)
                .startTime(Instant.now().plusSeconds(3600))
                .endTime(Instant.now().plusSeconds(7200))
                .build();
    }
}
