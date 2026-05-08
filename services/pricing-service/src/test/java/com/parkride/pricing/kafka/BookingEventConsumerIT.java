package com.parkride.pricing.kafka;

import com.parkride.events.BookingEvent;
import com.parkride.pricing.repository.PricingRuleRepository;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/**
 * Integration tests for {@link com.parkride.pricing.kafka.BookingEventConsumer}.
 *
 * <p>Verifies the full pipeline:
 * {@code EmbeddedKafka → BookingEventConsumer → OccupancyTrackingService → StringRedisTemplate}.
 *
 * <p>{@link StringRedisTemplate} is mocked — no Redis container required.
 * The test only asserts that the correct Redis operation (increment/decrement) is triggered.
 *
 * <p>JPA/Flyway/Redis are all excluded — the consumer has no DB or cache dependency.
 * {@link PricingRuleRepository} is mocked to satisfy the PricingEngineService bean dependency
 * without creating a real EntityManagerFactory.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"booking-events"})
@TestPropertySource(properties = {
    "spring.kafka.topics.booking-events=booking-events",
    "spring.kafka.consumer.group-id=pricing-it-consumers",
    "spring.kafka.consumer.auto-offset-reset=earliest",
    // Exclude all infra that the consumer doesn't need.
    // JpaRepositoriesAutoConfiguration MUST be excluded alongside Hibernate to prevent
    // Spring Data JPA from trying to create PricingRuleRepository without an EntityManagerFactory.
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
    "security.jwt.secret=test-secret-minimum-32-characters-long"
})
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("null")
class BookingEventConsumerIT {

    @Autowired
    EmbeddedKafkaBroker embeddedKafkaBroker;

    /**
     * Placeholder mock — satisfies PricingEngineService dependency without real JPA.
     * BookingEventConsumer does not use this repository.
     */
    @MockitoBean
    PricingRuleRepository pricingRuleRepository;

    /** Mock the Redis template — we verify calls, not real Redis state. */
    @MockitoBean
    StringRedisTemplate stringRedisTemplate;

    KafkaTemplate<String, Object> kafkaTemplate;

    static final String TOPIC = "booking-events";
    static final UUID   LOT_ID = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(valueOps.decrement(anyString())).thenReturn(0L);

        Map<String, Object> props = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        kafkaTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    private BookingEvent event(BookingEvent.EventType type) {
        return BookingEvent.builder()
                .bookingId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .lotId(LOT_ID)
                .slotId(UUID.randomUUID())
                .eventType(type)
                .occurredAt(Instant.now())
                .startTime(Instant.now())
                .endTime(Instant.now().plusSeconds(3600))
                .amount(BigDecimal.valueOf(100))
                .userEmail("test@example.com")
                .userName("Test User")
                .slotLabel("A-01")
                .build();
    }

    // ── BOOKING_CONFIRMED → increment ─────────────────────────────────────────

    @Test @Order(1)
    @DisplayName("BOOKING_CONFIRMED → Redis increment called for the lot")
    void bookingConfirmed_incrementsOccupancy() {
        kafkaTemplate.send(TOPIC, event(BookingEvent.EventType.BOOKING_CONFIRMED));

        await().atMost(10, SECONDS).untilAsserted(() ->
            verify(stringRedisTemplate.opsForValue())
                .increment("occupancy:" + LOT_ID));
    }

    // ── BOOKING_CANCELLED → decrement ─────────────────────────────────────────

    @Test @Order(2)
    @DisplayName("BOOKING_CANCELLED → Redis decrement called for the lot")
    void bookingCancelled_decrementsOccupancy() {
        kafkaTemplate.send(TOPIC, event(BookingEvent.EventType.BOOKING_CANCELLED));

        await().atMost(10, SECONDS).untilAsserted(() ->
            verify(stringRedisTemplate.opsForValue())
                .decrement("occupancy:" + LOT_ID));
    }

    // ── BOOKING_NO_SHOW → decrement ───────────────────────────────────────────

    @Test @Order(3)
    @DisplayName("BOOKING_NO_SHOW → Redis decrement called for the lot")
    void bookingNoShow_decrementsOccupancy() {
        kafkaTemplate.send(TOPIC, event(BookingEvent.EventType.BOOKING_NO_SHOW));

        await().atMost(10, SECONDS).untilAsserted(() ->
            verify(stringRedisTemplate.opsForValue())
                .decrement("occupancy:" + LOT_ID));
    }

    // ── SLOT_CHECKED_IN → ignored ─────────────────────────────────────────────

    @Test @Order(4)
    @DisplayName("SLOT_CHECKED_IN → no Redis call (event type is ignored)")
    @SuppressWarnings("unchecked")
    void slotCheckedIn_noOccupancyChange() {
        reset(stringRedisTemplate);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);

        kafkaTemplate.send(TOPIC, event(BookingEvent.EventType.SLOT_CHECKED_IN));

        await().pollDelay(4, SECONDS).atMost(5, SECONDS)
               .untilAsserted(() -> {
                   verify(valueOps, never()).increment(anyString());
                   verify(valueOps, never()).decrement(anyString());
               });
    }

    // ── Occupancy never goes negative ─────────────────────────────────────────

    @Test @Order(5)
    @DisplayName("BOOKING_CANCELLED with decrement returning negative → reset to 0")
    @SuppressWarnings("unchecked")
    void bookingCancelled_decrementNegative_resetsToZero() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.decrement(anyString())).thenReturn(-1L); // simulate drift

        kafkaTemplate.send(TOPIC, event(BookingEvent.EventType.BOOKING_CANCELLED));

        await().atMost(10, SECONDS).untilAsserted(() ->
            // Service should call set("occupancy:{lotId}", "0") to floor at zero
            verify(stringRedisTemplate.opsForValue()).set("occupancy:" + LOT_ID, "0"));
    }
}
