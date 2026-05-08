package com.parkride.notification.kafka;

import com.parkride.events.BookingEvent;
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
 * Integration tests for {@link com.parkride.notification.service.BookingEventConsumer}.
 * Verifies: Kafka deserialization → dispatch routing → JavaMailSender invocation.
 * JavaMailSender is mocked (no SMTP needed); template rendering is covered by EmailServiceTest.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"booking-events", "payment-events"})
@TestPropertySource("classpath:application-test.properties")
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("null")
class BookingEventConsumerIT {

    @MockitoBean
    JavaMailSender mailSender;

    @Autowired
    EmbeddedKafkaBroker embeddedKafkaBroker;

    KafkaTemplate<String, Object> kafkaTemplate;

    static final String TOPIC      = "booking-events";
    static final String USER_EMAIL = "booking.user@example.com";

    @BeforeEach
    void setUp() {
        Map<String, Object> props = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        kafkaTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        when(mailSender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getDefaultInstance(new Properties())));
    }

    private BookingEvent event(BookingEvent.EventType type) {
        return BookingEvent.builder()
                .bookingId(UUID.randomUUID()).userId(UUID.randomUUID())
                .lotId(UUID.randomUUID()).slotId(UUID.randomUUID())
                .eventType(type).occurredAt(Instant.now())
                .startTime(Instant.now()).endTime(Instant.now().plusSeconds(3600))
                .amount(BigDecimal.valueOf(150))
                .userEmail(USER_EMAIL).userName("Booking User")
                .slotLabel("B-12 / Ground Floor").userPhone("+919876543210")
                .qrCodeToken(type == BookingEvent.EventType.BOOKING_CONFIRMED ? "QR-IT-001" : null)
                .build();
    }

    @Test @Order(1)
    @DisplayName("BOOKING_CONFIRMED → JavaMailSender.send() invoked")
    void bookingConfirmed_mailSent() {
        kafkaTemplate.send(TOPIC, event(BookingEvent.EventType.BOOKING_CONFIRMED));
        await().atMost(10, SECONDS).untilAsserted(() -> verify(mailSender).send(any(MimeMessage.class)));
    }

    @Test @Order(2)
    @DisplayName("BOOKING_CANCELLED → JavaMailSender.send() invoked")
    void bookingCancelled_mailSent() {
        kafkaTemplate.send(TOPIC, event(BookingEvent.EventType.BOOKING_CANCELLED));
        await().atMost(10, SECONDS).untilAsserted(() -> verify(mailSender).send(any(MimeMessage.class)));
    }

    @Test @Order(3)
    @DisplayName("BOOKING_NO_SHOW → JavaMailSender.send() invoked")
    void bookingNoShow_mailSent() {
        kafkaTemplate.send(TOPIC, event(BookingEvent.EventType.BOOKING_NO_SHOW));
        await().atMost(10, SECONDS).untilAsserted(() -> verify(mailSender).send(any(MimeMessage.class)));
    }

    @Test @Order(4)
    @DisplayName("SLOT_CHECKED_IN → no mail sent")
    void slotCheckedIn_noMailSent() {
        kafkaTemplate.send(TOPIC, event(BookingEvent.EventType.SLOT_CHECKED_IN));
        await().pollDelay(4, SECONDS).atMost(5, SECONDS)
               .untilAsserted(() -> verifyNoInteractions(mailSender));
    }

    @Test @Order(5)
    @DisplayName("Null userEmail → consumer acks without crash, no mail sent")
    void nullEmail_noMailSent() {
        BookingEvent e = BookingEvent.builder()
                .bookingId(UUID.randomUUID()).userId(UUID.randomUUID())
                .lotId(UUID.randomUUID()).slotId(UUID.randomUUID())
                .eventType(BookingEvent.EventType.BOOKING_CONFIRMED)
                .occurredAt(Instant.now())
                .startTime(Instant.now()).endTime(Instant.now().plusSeconds(3600))
                .amount(BigDecimal.valueOf(100))
                .userEmail(null).userName("Ghost").slotLabel("A-01").build();
        kafkaTemplate.send(TOPIC, e);
        await().pollDelay(4, SECONDS).atMost(5, SECONDS)
               .untilAsserted(() -> verifyNoInteractions(mailSender));
    }
}
