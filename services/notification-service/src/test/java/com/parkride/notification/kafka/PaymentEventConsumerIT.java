package com.parkride.notification.kafka;

import com.parkride.events.PaymentEvent;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests for {@link com.parkride.notification.service.PaymentEventConsumer}.
 * Verifies: Kafka deserialization → dispatch routing → JavaMailSender invocation.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"booking-events", "payment-events"})
@TestPropertySource("classpath:application-test.properties")
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("null")
class PaymentEventConsumerIT {

    @MockitoBean
    JavaMailSender mailSender;

    @Autowired
    EmbeddedKafkaBroker embeddedKafkaBroker;

    KafkaTemplate<String, Object> kafkaTemplate;

    static final String TOPIC      = "payment-events";
    static final String USER_EMAIL = "payment.user@example.com";

    @BeforeEach
    void setUp() {
        Map<String, Object> props = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        kafkaTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        when(mailSender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getDefaultInstance(new Properties())));
    }

    private PaymentEvent event(PaymentEvent.EventType type) {
        return PaymentEvent.builder()
                .transactionId(UUID.randomUUID())
                .idempotencyKey(UUID.randomUUID().toString())
                .userId(UUID.randomUUID()).walletId(UUID.randomUUID())
                .eventType(type).occurredAt(Instant.now())
                .amount(BigDecimal.valueOf(200)).balanceAfter(BigDecimal.valueOf(800))
                .referenceId(UUID.randomUUID())
                .userEmail(USER_EMAIL).userName("Payment User").userPhone("+919876543210")
                .failureReason(type == PaymentEvent.EventType.PAYMENT_FAILED ? "Insufficient balance" : null)
                .build();
    }

    @Test @Order(1)
    @DisplayName("PAYMENT_CHARGED → JavaMailSender.send() invoked")
    void paymentCharged_mailSent() {
        kafkaTemplate.send(TOPIC, event(PaymentEvent.EventType.PAYMENT_CHARGED));
        await().atMost(10, SECONDS).untilAsserted(() -> verify(mailSender).send(any(MimeMessage.class)));
    }

    @Test @Order(2)
    @DisplayName("REFUND_PROCESSED → JavaMailSender.send() invoked")
    void refundProcessed_mailSent() {
        kafkaTemplate.send(TOPIC, event(PaymentEvent.EventType.REFUND_PROCESSED));
        await().atMost(10, SECONDS).untilAsserted(() -> verify(mailSender).send(any(MimeMessage.class)));
    }

    @Test @Order(3)
    @DisplayName("PAYMENT_FAILED → JavaMailSender.send() invoked")
    void paymentFailed_mailSent() {
        kafkaTemplate.send(TOPIC, event(PaymentEvent.EventType.PAYMENT_FAILED));
        await().atMost(10, SECONDS).untilAsserted(() -> verify(mailSender).send(any(MimeMessage.class)));
    }

    @Test @Order(4)
    @DisplayName("Null userEmail → consumer acks without crash, no mail sent")
    void nullEmail_noMailSent() {
        PaymentEvent e = PaymentEvent.builder()
                .transactionId(UUID.randomUUID())
                .idempotencyKey(UUID.randomUUID().toString())
                .userId(UUID.randomUUID()).walletId(UUID.randomUUID())
                .eventType(PaymentEvent.EventType.PAYMENT_CHARGED)
                .occurredAt(Instant.now())
                .amount(BigDecimal.valueOf(100)).balanceAfter(BigDecimal.valueOf(400))
                .referenceId(UUID.randomUUID())
                .userEmail(null).userName("Ghost").build();
        kafkaTemplate.send(TOPIC, e);
        await().pollDelay(4, SECONDS).atMost(5, SECONDS)
               .untilAsserted(() -> verifyNoInteractions(mailSender));
    }
}
