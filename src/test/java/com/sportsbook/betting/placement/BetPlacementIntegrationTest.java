package com.sportsbook.betting.placement;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.error.InsufficientBalanceException;
import com.sportsbook.betting.error.RiskLimitException;
import com.sportsbook.betting.infrastructure.id.UuidV7;
import com.sportsbook.betting.outbox.AvroSerializer;
import com.sportsbook.betting.outbox.OutboxEvent;
import com.sportsbook.betting.outbox.OutboxEventRepository;
import com.sportsbook.betting.persistence.BetRepository;
import com.sportsbook.betting.placement.PlaceBetCommand.SelectionInput;
import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.domain.BetStatus;
import com.sportsbook.protocol.event.BetPlacedRequested;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.protocol.value.Odds;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end placement orchestration (ADR-0017) against real PostgreSQL + Redis and a WireMock
 * risk/wallet stub. Kafka is excluded — task 6 only writes the outbox row; draining it is task 7.
 */
@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
    })
@Testcontainers
@ActiveProfiles("test")
class BetPlacementIntegrationTest {

  private static final String RISK_PATH = "/internal/v1/risk/check";
  private static final String DEBIT_PATH = "/internal/v1/wallet/transactions/debit";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  static WireMockServer wireMock = new WireMockServer(options().dynamicPort());

  static {
    wireMock.start();
  }

  @AfterAll
  static void stopWireMock() {
    wireMock.stop();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    registry.add("betting.clients.risk-base-url", wireMock::baseUrl);
    registry.add("betting.clients.wallet-base-url", wireMock::baseUrl);
  }

  @Autowired BetPlacementService placement;
  @Autowired BetRepository bets;
  @Autowired OutboxEventRepository outbox;
  @Autowired StringRedisTemplate redisTemplate;

  private final UUID userId = UuidV7.generate();
  private final UUID eventId = UuidV7.generate();
  private final UUID marketId = UuidV7.generate();
  private final UUID selectionId = UuidV7.generate();

  @BeforeEach
  void setUp() {
    wireMock.resetAll();
    outbox.deleteAll();
    bets.deleteAll();
    // Seed the odds-feed cache so slippage passes: market OPEN, current odds == submitted.
    redisTemplate.opsForValue().set("market:" + eventId + ":" + marketId, "OPEN");
    redisTemplate
        .opsForValue()
        .set("odds:" + eventId + ":" + marketId + ":" + selectionId, "2.0000");
  }

  private PlaceBetCommand single(String idempotencyKey) {
    return new PlaceBetCommand(
        userId,
        new BetSlipType.Single(),
        List.of(new SelectionInput(eventId, marketId, selectionId, Odds.ofDecimal("2.0000"))),
        Money.krw(10_000),
        IdempotencyKey.of(idempotencyKey));
  }

  private void stubRiskApproved() {
    wireMock.stubFor(post(urlEqualTo(RISK_PATH)).willReturn(okJson("{\"approved\":true}")));
  }

  private void stubWalletDebitOk() {
    wireMock.stubFor(
        post(urlEqualTo(DEBIT_PATH))
            .willReturn(okJson("{\"operationGroupId\":\"" + UuidV7.generate() + "\"}")));
  }

  @Test
  @DisplayName("happy path: accepts, computes payout, writes one BetPlacedRequested outbox row")
  void accepts() {
    stubRiskApproved();
    stubWalletDebitOk();

    Bet result = placement.place(single("idem-accept"));

    assertThat(result.status()).isEqualTo(BetStatus.ACCEPTED);
    assertThat(result.betReference()).startsWith("B-");
    assertThat(result.maxPayout()).isEqualTo(Money.krw(20_000)); // 10,000 * 2.0

    List<OutboxEvent> events = outbox.findAll();
    assertThat(events).hasSize(1);
    OutboxEvent event = events.get(0);
    assertThat(event.topic()).isEqualTo("bet.placed.v1");
    assertThat(event.schemaName()).isEqualTo("BetPlacedRequested");
    assertThat(event.partitionKey()).isEqualTo(userId.toString());
    BetPlacedRequested decoded =
        AvroSerializer.deserialize(event.payload(), BetPlacedRequested.class);
    assertThat(decoded.getBetId()).hasToString(result.betId().toString());
  }

  @Test
  @DisplayName("risk decline: REJECTED before wallet is ever called, no outbox row")
  void riskDecline() {
    wireMock.stubFor(
        post(urlEqualTo(RISK_PATH))
            .willReturn(okJson("{\"approved\":false,\"rejectionReason\":\"DAILY_LIMIT\"}")));

    assertThatThrownBy(() -> placement.place(single("idem-risk")))
        .isInstanceOf(RiskLimitException.class);

    Bet persisted = bets.findByIdempotencyKey("idem-risk").orElseThrow();
    assertThat(persisted.status()).isEqualTo(BetStatus.REJECTED);
    assertThat(persisted.rejectionReason()).isEqualTo("LIMIT_EXCEEDED");
    wireMock.verify(exactly(0), postRequestedFor(urlEqualTo(DEBIT_PATH)));
    assertThat(outbox.count()).isZero();
  }

  @Test
  @DisplayName("wallet decline: REJECTED after debit fails, no outbox row")
  void walletDecline() {
    stubRiskApproved();
    wireMock.stubFor(
        post(urlEqualTo(DEBIT_PATH))
            .willReturn(
                aResponse()
                    .withStatus(422)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody(
                        "{\"code\":\"WALLET_INSUFFICIENT_BALANCE\",\"detail\":\"too low\"}")));

    assertThatThrownBy(() -> placement.place(single("idem-wallet")))
        .isInstanceOf(InsufficientBalanceException.class);

    Bet persisted = bets.findByIdempotencyKey("idem-wallet").orElseThrow();
    assertThat(persisted.status()).isEqualTo(BetStatus.REJECTED);
    assertThat(persisted.rejectionReason()).isEqualTo("INSUFFICIENT_BALANCE");
    assertThat(outbox.count()).isZero();
  }

  @Test
  @DisplayName("idempotent retry: same key -> one accepted bet, one debit, same betId")
  void idempotentRetry() {
    stubRiskApproved();
    stubWalletDebitOk();

    Bet first = placement.place(single("idem-dup"));
    Bet second = placement.place(single("idem-dup"));

    assertThat(second.betId()).isEqualTo(first.betId());
    assertThat(bets.findAll()).hasSize(1);
    assertThat(outbox.count()).isEqualTo(1);
    // The wallet was debited exactly once — the replay short-circuited before any HTTP call.
    wireMock.verify(exactly(1), postRequestedFor(urlEqualTo(DEBIT_PATH)));
  }
}
