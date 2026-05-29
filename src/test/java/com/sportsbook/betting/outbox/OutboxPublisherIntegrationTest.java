package com.sportsbook.betting.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.domain.BetLeg;
import com.sportsbook.betting.infrastructure.id.UuidV7;
import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.event.BetPlacedRequested;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.protocol.value.Odds;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Drains an {@code outbox_event} row to an embedded Kafka broker and asserts the publisher sends
 * the Avro payload under the user partition key and stamps {@code published_at} on ack.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(
    partitions = 1,
    topics = {BetEventFactory.TOPIC},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@ActiveProfiles("test")
class OutboxPublisherIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
  }

  @Autowired OutboxEventRepository outbox;
  @Autowired OutboxPublisher publisher;
  @Autowired BetEventFactory eventFactory;
  @Autowired EmbeddedKafkaBroker broker;

  private Consumer<String, byte[]> consumer;

  @AfterEach
  void closeConsumer() {
    if (consumer != null) {
      consumer.close();
    }
    outbox.deleteAll();
  }

  private static Bet acceptedBet(UUID userId) {
    Bet bet =
        Bet.pending(
            UuidV7.generate(),
            userId,
            "B-2026-05-29-OUTBOX01",
            new BetSlipType.Single(),
            Money.krw(10_000),
            Money.krw(20_000),
            IdempotencyKey.of("idem-outbox"),
            List.of(
                BetLeg.create(
                    UuidV7.generate(),
                    UuidV7.generate(),
                    UuidV7.generate(),
                    Odds.ofDecimal("2.0000"))),
            Instant.parse("2026-05-29T07:00:00Z"));
    bet.accept(Instant.parse("2026-05-29T07:00:01Z"));
    return bet;
  }

  private Consumer<String, byte[]> newConsumer() {
    Map<String, Object> props = KafkaTestUtils.consumerProps("outbox-test", "true", broker);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    Consumer<String, byte[]> c = new KafkaConsumer<>(props);
    c.subscribe(List.of(BetEventFactory.TOPIC));
    return c;
  }

  @Test
  @DisplayName("publishes the Avro payload under the user key and stamps published_at")
  void publishesAndStamps() {
    UUID userId = UuidV7.generate();
    Bet bet = acceptedBet(userId);
    OutboxEvent event = eventFactory.placedRequested(bet, Instant.parse("2026-05-29T07:00:01Z"));
    outbox.save(event);
    consumer = newConsumer();

    publisher.publishPending();

    ConsumerRecords<String, byte[]> records =
        KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));
    assertThat(records.count()).isEqualTo(1);
    ConsumerRecord<String, byte[]> record = records.iterator().next();
    assertThat(record.key()).isEqualTo(userId.toString());
    BetPlacedRequested decoded =
        AvroSerializer.deserialize(record.value(), BetPlacedRequested.class);
    assertThat(decoded.getBetId()).hasToString(bet.betId().toString());
    assertThat(decoded.getUserId()).hasToString(userId.toString());

    assertThat(outbox.findById(event.eventId()).orElseThrow().isPublished()).isTrue();
  }

  @Test
  @DisplayName("no unpublished rows -> publisher is a no-op")
  void noOpWhenEmpty() {
    consumer = newConsumer();

    publisher.publishPending();

    assertThat(KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2)).count()).isZero();
  }
}
