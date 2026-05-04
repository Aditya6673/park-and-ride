package com.parkride.payment.service;

import com.parkride.events.BookingEvent;
import com.parkride.events.PaymentEvent;
import com.parkride.payment.domain.Transaction;
import com.parkride.payment.domain.TransactionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Kafka consumer for {@code booking-events} topic.
 *
 * <p><b>Idempotency:</b> Each message produces an {@code idempotencyKey} of the form
 * {@code bookingId:BOOKING_CONFIRMED} or {@code bookingId:BOOKING_CANCELLED}.
 * The UNIQUE constraint on {@code transactions.idempotency_key} guarantees that
 * even if Kafka delivers the same message twice, only one transaction is recorded.
 *
 * <p><b>Manual acknowledgment:</b> {@code AckMode.MANUAL} — the offset is committed
 * only after successful processing. On failure the message is retried.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingEventConsumer {

    private final WalletService walletService;
    private final PaymentEventPublisher eventPublisher;

    @KafkaListener(
        topics = "${spring.kafka.topics.booking-events}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(
            @Payload BookingEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment ack) {

        log.debug("Received BookingEvent [type={}, bookingId={}, partition={}, offset={}]",
                event.getEventType(), event.getBookingId(), partition, offset);

        try {
            switch (event.getEventType()) {
                case BOOKING_CONFIRMED -> handleConfirmed(event);
                case BOOKING_CANCELLED, BOOKING_NO_SHOW -> handleCancelled(event);
                default -> log.debug("Ignoring BookingEvent type: {}", event.getEventType());
            }
            ack.acknowledge();
        } catch (Exception ex) {
            log.error("Error processing BookingEvent [bookingId={}]: {}",
                    event.getBookingId(), ex.getMessage(), ex);
            // Do NOT acknowledge — Kafka will redeliver for retry
        }
    }

    // ─── Handlers ─────────────────────────────────────────────────────────────

    private void handleConfirmed(BookingEvent event) {
        if (event.getAmount() == null || event.getAmount().signum() <= 0) {
            log.info("BookingEvent CONFIRMED with no charge amount — skipping debit [bookingId={}]",
                    event.getBookingId());
            return;
        }

        String idempotencyKey = event.getBookingId() + ":" + BookingEvent.EventType.BOOKING_CONFIRMED.name();
        Transaction tx = walletService.debit(
                event.getUserId(),
                event.getAmount(),
                idempotencyKey,
                event.getBookingId()
        );

        publishPaymentEvent(event, tx,
                tx.getStatus() == TransactionStatus.SUCCESS
                        ? PaymentEvent.EventType.PAYMENT_CHARGED
                        : PaymentEvent.EventType.PAYMENT_FAILED,
                tx.getFailureReason());
    }

    private void handleCancelled(BookingEvent event) {
        if (event.getAmount() == null || event.getAmount().signum() <= 0) {
            log.info("BookingEvent CANCELLED with no refund amount — skipping credit [bookingId={}]",
                    event.getBookingId());
            return;
        }

        String idempotencyKey = event.getBookingId() + ":" + event.getEventType().name();
        Transaction tx = walletService.credit(
                event.getUserId(),
                event.getAmount(),
                idempotencyKey,
                event.getBookingId()
        );

        publishPaymentEvent(event, tx, PaymentEvent.EventType.REFUND_PROCESSED, null);
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private void publishPaymentEvent(BookingEvent booking, Transaction tx,
                                     PaymentEvent.EventType eventType, String failureReason) {
        PaymentEvent paymentEvent = PaymentEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(eventType)
                .occurredAt(Instant.now())
                .transactionId(tx.getId())
                .idempotencyKey(tx.getIdempotencyKey())
                .userId(booking.getUserId())
                .walletId(tx.getWallet().getId())
                .amount(tx.getAmount())
                .balanceAfter(tx.getWallet().getBalance())
                .referenceId(booking.getBookingId())
                .referenceType("BOOKING")
                .status(tx.getStatus().name())
                .failureReason(failureReason)
                .build();

        eventPublisher.publish(paymentEvent);
    }
}
