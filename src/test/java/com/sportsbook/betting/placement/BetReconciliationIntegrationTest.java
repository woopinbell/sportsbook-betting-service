package com.sportsbook.betting.placement;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.domain.BetLeg;
import com.sportsbook.betting.infrastructure.id.UuidV7;
import com.sportsbook.betting.outbox.OutboxEventRepository;
import com.sportsbook.betting.persistence.BetRepository;
import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.domain.BetStatus;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.protocol.value.Odds;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Reconciliation of stale PENDING bets (ADR-0017 compensation). A bet whose accept transaction was
 * lost is resolved by an idempotent re-debit: debit ok -> roll forward to ACCEPTED, insufficient ->
 * roll back to REJECTED, wallet down -> left PENDING for the next tick. pending-timeout is 0s in
 * application-test.yml so the bet (created in the past) is immediately eligible.
 */
@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
    })
@Testcontainers
@ActiveProfiles("test")
class BetReconciliationIntegrationTest {

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
    registry.add("betting.clients.wallet-base-url", wireMock::baseUrl);
    registry.add("betting.clients.risk-base-url", wireMock::baseUrl);
  }

  @Autowired BetReconciliationJob reconciliation;
  @Autowired BetStore store;
  @Autowired BetRepository bets;
  @Autowired OutboxEventRepository outbox;

  private final UUID userId = UuidV7.generate();

  @BeforeEach
  void setUp() {
    wireMock.resetAll();
    outbox.deleteAll();
    bets.deleteAll();
  }

  private UUID stalePending(String reference, String idempotencyKey) {
    UUID betId = UuidV7.generate();
    Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
    Bet bet =
        Bet.pending(
            betId,
            userId,
            reference,
            new BetSlipType.Single(),
            Money.krw(10_000),
            Money.krw(20_000),
            IdempotencyKey.of(idempotencyKey),
            List.of(
                BetLeg.create(
                    UuidV7.generate(),
                    UuidV7.generate(),
                    UuidV7.generate(),
                    Odds.ofDecimal("2.0000"))),
            past);
    store.savePending(bet);
    return betId;
  }

  @Test
  @DisplayName("debit confirms -> roll forward to ACCEPTED + outbox row")
  void rollForward() {
    wireMock.stubFor(
        post(urlEqualTo(DEBIT_PATH))
            .willReturn(okJson("{\"operationGroupId\":\"" + UuidV7.generate() + "\"}")));
    UUID betId = stalePending("B-2026-05-29-RECON-FW", "recon-fwd");

    reconciliation.reconcile();

    assertThat(bets.findById(betId).orElseThrow().status()).isEqualTo(BetStatus.ACCEPTED);
    assertThat(outbox.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("debit never landed (insufficient) -> roll back to REJECTED, no outbox row")
  void rollBack() {
    wireMock.stubFor(
        post(urlEqualTo(DEBIT_PATH))
            .willReturn(
                aResponse()
                    .withStatus(422)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody(
                        "{\"code\":\"WALLET_INSUFFICIENT_BALANCE\",\"detail\":\"too low\"}")));
    UUID betId = stalePending("B-2026-05-29-RECON-BK", "recon-back");

    reconciliation.reconcile();

    Bet reconciled = bets.findById(betId).orElseThrow();
    assertThat(reconciled.status()).isEqualTo(BetStatus.REJECTED);
    assertThat(reconciled.rejectionReason()).isEqualTo("RECONCILED_NO_DEBIT");
    assertThat(outbox.count()).isZero();
  }

  @Test
  @DisplayName("wallet unavailable -> left PENDING for the next tick")
  void defersWhenWalletDown() {
    wireMock.stubFor(post(urlEqualTo(DEBIT_PATH)).willReturn(aResponse().withStatus(503)));
    UUID betId = stalePending("B-2026-05-29-RECON-DN", "recon-down");

    reconciliation.reconcile();

    assertThat(bets.findById(betId).orElseThrow().status()).isEqualTo(BetStatus.PENDING);
    assertThat(outbox.count()).isZero();
  }
}
