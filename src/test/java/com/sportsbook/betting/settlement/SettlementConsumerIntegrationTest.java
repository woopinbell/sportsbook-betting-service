package com.sportsbook.betting.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.domain.BetLeg;
import com.sportsbook.betting.domain.VoidReason;
import com.sportsbook.betting.infrastructure.id.UuidV7;
import com.sportsbook.betting.outbox.AvroSerializer;
import com.sportsbook.betting.persistence.BetRepository;
import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.domain.BetStatus;
import com.sportsbook.protocol.domain.SettlementResult;
import com.sportsbook.protocol.event.BetSettled;
import com.sportsbook.protocol.event.BetVoided;
import com.sportsbook.protocol.event.SettlementResultAvro;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.protocol.value.Odds;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
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
 * End-to-end coverage for the settlement consumer (ADR-0006): a real Kafka record carrying an Avro
 * {@code BetSettled} / {@code BetVoided} drives an {@code ACCEPTED} bet to its terminal state. The
 * idempotency and unknown-bet guards are exercised through the transactional service directly so
 * they are deterministic (no broker timing) — the listener only decodes and delegates.
 *
 * <p>{@code @EmbeddedKafka} (broker) plus Testcontainers PostgreSQL / Redis, mirroring the outbox
 * test. The settlement listener is parked in the test profile, so this class re-enables it via
 * {@code betting.kafka.consumer.auto-startup}.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(
    partitions = 1,
    topics = {SettlementResultListener.SETTLED_TOPIC, SettlementResultListener.VOIDED_TOPIC},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@ActiveProfiles("test")
class SettlementConsumerIntegrationTest {

  private static final UUID USER = UUID.fromString("00000000-0000-7000-8000-00000000aaaa");
  private static final Instant T0 = Instant.parse("2026-05-29T07:00:00Z");
  private static final Instant T1 = Instant.parse("2026-05-29T07:00:01Z");
  private static final Instant SETTLED_AT = Instant.parse("2026-05-29T09:30:00Z");

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
    // Park-by-default in the test profile; this class needs the listener running.
    registry.add("betting.kafka.consumer.auto-startup", () -> true);
  }

  @Autowired BetRepository bets;
  @Autowired BetSettlementService settlementService;
  @Autowired EmbeddedKafkaBroker broker;

  @Test
  @DisplayName("BetSettled consumed: ACCEPTED bet becomes SETTLED with the recorded payout")
  void consumesBetSettled() {
    Bet bet = persistAcceptedBet("B-2026-05-29-SET00001", "idem-settle-1");

    publish(
        SettlementResultListener.SETTLED_TOPIC, settled(bet, SettlementResultAvro.WON, 18_500L));

    awaitStatus(bet.betId(), BetStatus.SETTLED);
    Bet settled = bets.findById(bet.betId()).orElseThrow();
    assertThat(settled.settlementResult()).isEqualTo(SettlementResult.WON);
    assertThat(settled.settledPayout()).isEqualTo(Money.krw(18_500));
    assertThat(settled.resolvedAt()).isEqualTo(SETTLED_AT);
  }

  @Test
  @DisplayName("BetVoided consumed: ACCEPTED bet becomes VOIDED with the recorded reason")
  void consumesBetVoided() {
    Bet bet = persistAcceptedBet("B-2026-05-29-VOID0001", "idem-void-1");

    publish(
        SettlementResultListener.VOIDED_TOPIC,
        voided(bet, com.sportsbook.protocol.event.VoidReason.EVENT_CANCELLED));

    awaitStatus(bet.betId(), BetStatus.VOIDED);
    Bet voided = bets.findById(bet.betId()).orElseThrow();
    assertThat(voided.voidReason()).isEqualTo(VoidReason.EVENT_CANCELLED);
    assertThat(voided.resolvedAt()).isEqualTo(SETTLED_AT);
  }

  @Test
  @DisplayName("settling the same bet twice is idempotent (no second write)")
  void settleIsIdempotent() {
    Bet bet = persistAcceptedBet("B-2026-05-29-IDEM0001", "idem-settle-dup");

    settlementService.applySettled(
        bet.betId(), SettlementResult.WON, Money.krw(18_500), SETTLED_AT);
    long versionAfterFirst = bets.findById(bet.betId()).orElseThrow().version();
    settlementService.applySettled(
        bet.betId(), SettlementResult.WON, Money.krw(18_500), SETTLED_AT);

    Bet after = bets.findById(bet.betId()).orElseThrow();
    assertThat(after.status()).isEqualTo(BetStatus.SETTLED);
    assertThat(after.version()).isEqualTo(versionAfterFirst);
  }

  @Test
  @DisplayName("voiding the same bet twice is idempotent (no second write)")
  void voidIsIdempotent() {
    Bet bet = persistAcceptedBet("B-2026-05-29-IDEM0002", "idem-void-dup");

    settlementService.applyVoided(bet.betId(), VoidReason.ADMIN_VOID, SETTLED_AT);
    long versionAfterFirst = bets.findById(bet.betId()).orElseThrow().version();
    settlementService.applyVoided(bet.betId(), VoidReason.ADMIN_VOID, SETTLED_AT);

    Bet after = bets.findById(bet.betId()).orElseThrow();
    assertThat(after.status()).isEqualTo(BetStatus.VOIDED);
    assertThat(after.version()).isEqualTo(versionAfterFirst);
  }

  @Test
  @DisplayName("settlement for an unknown bet is surfaced (routes to retry / DLT)")
  void unknownBetIsSurfaced() {
    assertThatThrownBy(
            () ->
                settlementService.applySettled(
                    UuidV7.generate(), SettlementResult.WON, Money.krw(1), SETTLED_AT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unknown betId");
  }

  private Bet persistAcceptedBet(String reference, String idem) {
    Bet bet =
        Bet.pending(
            UuidV7.generate(),
            USER,
            reference,
            new BetSlipType.Single(),
            Money.krw(10_000),
            Money.krw(18_500),
            IdempotencyKey.of(idem),
            List.of(
                BetLeg.create(
                    UuidV7.generate(),
                    UuidV7.generate(),
                    UuidV7.generate(),
                    Odds.ofDecimal("1.8500"))),
            T0);
    bet.accept(T1);
    return bets.save(bet);
  }

  private static BetSettled settled(Bet bet, SettlementResultAvro result, long payoutMinor) {
    return BetSettled.newBuilder()
        .setBetId(bet.betId().toString())
        .setUserId(bet.userId().toString())
        .setEventId(UuidV7.generate().toString())
        .setResult(result)
        .setStake(avroMoney(bet.stake().amount()))
        .setPayout(avroMoney(payoutMinor))
        .setSettledAt(SETTLED_AT)
        .build();
  }

  private static BetVoided voided(Bet bet, com.sportsbook.protocol.event.VoidReason reason) {
    return BetVoided.newBuilder()
        .setBetId(bet.betId().toString())
        .setUserId(bet.userId().toString())
        .setEventId(UuidV7.generate().toString())
        .setReason(reason)
        .setRefund(avroMoney(bet.stake().amount()))
        .setVoidedAt(SETTLED_AT)
        .build();
  }

  private static com.sportsbook.protocol.event.Money avroMoney(long amount) {
    return com.sportsbook.protocol.event.Money.newBuilder()
        .setAmount(amount)
        .setCurrency("KRW")
        .build();
  }

  private void publish(String topic, SpecificRecord record) {
    Map<String, Object> props = KafkaTestUtils.producerProps(broker);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    // Same key the publisher uses (eventId) is not material here; one partition.
    try (Producer<String, byte[]> producer = new KafkaProducer<>(props)) {
      producer
          .send(new ProducerRecord<>(topic, AvroSerializer.serialize(record)))
          .get(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("publish to " + topic + " failed", e);
    }
  }

  private void awaitStatus(UUID betId, BetStatus expected) {
    Instant deadline = Instant.now().plusSeconds(15);
    while (Instant.now().isBefore(deadline)) {
      if (bets.findById(betId).map(b -> b.status() == expected).orElse(false)) {
        return;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    throw new AssertionError("Bet " + betId + " did not reach " + expected + " in time");
  }
}
