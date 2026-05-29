package com.sportsbook.betting.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.domain.BetLeg;
import com.sportsbook.betting.infrastructure.id.UuidV7;
import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.domain.BetStatus;
import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.protocol.value.Odds;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the {@link Bet} / {@link BetLeg} JPA mapping matches the Flyway schema (Hibernate {@code
 * ddl-auto: validate} fails context start otherwise) and that the two ADR-0005 uniqueness
 * guarantees hold at the DB level.
 *
 * <p>A {@code @DataJpaTest} slice against real PostgreSQL (Testcontainers, {@code replace = NONE})
 * — deliberately not a full {@code @SpringBootTest}, so it stays insulated from the growing
 * component graph (Redis odds checker, Kafka outbox, HTTP clients) and only needs a database.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class BetPersistenceIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired BetRepository bets;
  @Autowired TestEntityManager em;

  private static final Instant NOW = Instant.parse("2026-05-29T07:00:00Z");

  private static List<BetLeg> legs(int n) {
    return IntStream.range(0, n)
        .mapToObj(
            i ->
                BetLeg.create(
                    UuidV7.generate(),
                    UuidV7.generate(),
                    UuidV7.generate(),
                    Odds.ofDecimal("2.0500")))
        .toList();
  }

  private static Bet systemBet(String reference, String idem, List<BetLeg> legs) {
    return Bet.pending(
        UuidV7.generate(),
        UUID.fromString("00000000-0000-7000-8000-00000000aaaa"),
        reference,
        new BetSlipType.System(2, legs.size()),
        new Money(10_000L, Currency.KRW),
        new Money(86_152L, Currency.KRW),
        IdempotencyKey.of(idem),
        legs,
        NOW);
  }

  @Test
  @DisplayName("round-trips a SYSTEM slip with its legs through PostgreSQL")
  void round_trips_system_slip() {
    Bet saved = systemBet("B-2026-05-29-RTRIP001", "idem-roundtrip", legs(3));
    UUID betId = saved.betId();

    bets.save(saved);
    em.flush();
    em.clear(); // force a real DB read rather than a first-level cache hit

    Bet loaded = bets.findWithLegsByBetId(betId).orElseThrow();
    assertThat(loaded.status()).isEqualTo(BetStatus.PENDING);
    assertThat(loaded.betReference()).isEqualTo("B-2026-05-29-RTRIP001");
    assertThat(loaded.stake()).isEqualTo(new Money(10_000L, Currency.KRW));
    assertThat(loaded.maxPayout()).isEqualTo(new Money(86_152L, Currency.KRW));
    assertThat(loaded.slipType()).isEqualTo(new BetSlipType.System(2, 3));
    assertThat(loaded.legs()).hasSize(3).extracting(BetLeg::legIndex).containsExactly(0, 1, 2);
    assertThat(loaded.legs().get(0).oddsAtSubmission()).isEqualTo(Odds.ofDecimal("2.0500"));
  }

  @Test
  @DisplayName("rejects a duplicate Idempotency-Key (ADR-0005 DB unique)")
  void rejects_duplicate_idempotency_key() {
    bets.saveAndFlush(systemBet("B-2026-05-29-DUP-IDEM1", "idem-dup", legs(2)));

    Bet collision = systemBet("B-2026-05-29-DUP-IDEM2", "idem-dup", legs(2));
    assertThatThrownBy(() -> bets.saveAndFlush(collision))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("rejects a duplicate bet_reference (ADR-0003 human reference unique)")
  void rejects_duplicate_reference() {
    bets.saveAndFlush(systemBet("B-2026-05-29-DUP-REF", "idem-ref-a", legs(2)));

    Bet collision = systemBet("B-2026-05-29-DUP-REF", "idem-ref-b", legs(2));
    assertThatThrownBy(() -> bets.saveAndFlush(collision))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
