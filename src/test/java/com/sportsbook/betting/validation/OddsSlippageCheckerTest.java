package com.sportsbook.betting.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sportsbook.betting.domain.BetLeg;
import com.sportsbook.betting.error.MarketClosedException;
import com.sportsbook.betting.error.OddsDriftException;
import com.sportsbook.betting.infrastructure.id.UuidV7;
import com.sportsbook.betting.policy.BettingPolicyProperties;
import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.Odds;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Boundary coverage for the 3 % slippage rule with a mocked odds-feed cache. Submitted odds are
 * fixed at 2.0000, so the acceptance floor is 2.0000 * 0.97 = 1.9400.
 */
class OddsSlippageCheckerTest {

  private static final UUID E = UuidV7.generate();
  private static final UUID M = UuidV7.generate();
  private static final UUID S = UuidV7.generate();
  private static final String MARKET_KEY = "market:" + E + ":" + M;
  private static final String ODDS_KEY = "odds:" + E + ":" + M + ":" + S;

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> ops = mock(ValueOperations.class);

  private final BettingPolicyProperties policy =
      new BettingPolicyProperties(
          15,
          new BigDecimal("10000"),
          Map.of(Currency.KRW, 1_000L),
          Map.of(Currency.KRW, 1_000_000L),
          new BigDecimal("3"));

  private final OddsSlippageChecker checker = new OddsSlippageChecker(redis, policy);

  @BeforeEach
  void stubValueOps() {
    when(redis.opsForValue()).thenReturn(ops);
  }

  private void stub(String marketStatus, String currentOdds) {
    when(ops.get(MARKET_KEY)).thenReturn(marketStatus);
    when(ops.get(ODDS_KEY)).thenReturn(currentOdds);
  }

  private static List<BetLeg> oneLeg(String submittedOdds) {
    return List.of(BetLeg.create(E, M, S, Odds.ofDecimal(submittedOdds)));
  }

  @Test
  @DisplayName("accepts when live odds are unchanged")
  void accepts_unchanged() {
    stub("OPEN", "2.0000");
    assertThatCode(() -> checker.check(oneLeg("2.0000"))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("accepts exactly at the tolerance floor (1.9400)")
  void accepts_at_floor() {
    stub("OPEN", "1.9400");
    assertThatCode(() -> checker.check(oneLeg("2.0000"))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("rejects just below the tolerance floor (1.9399)")
  void rejects_just_below_floor() {
    stub("OPEN", "1.9399");
    assertThatThrownBy(() -> checker.check(oneLeg("2.0000")))
        .isInstanceOf(OddsDriftException.class)
        .hasMessageContaining("drifted beyond tolerance");
  }

  @Test
  @DisplayName("accepts when live odds improved for the user")
  void accepts_improved_odds() {
    stub("OPEN", "2.5000");
    assertThatCode(() -> checker.check(oneLeg("2.0000"))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("rejects a suspended market")
  void rejects_suspended() {
    stub("SUSPENDED", null);
    assertThatThrownBy(() -> checker.check(oneLeg("2.0000")))
        .isInstanceOf(MarketClosedException.class)
        .hasMessageContaining("not open");
  }

  @Test
  @DisplayName("rejects a closed market")
  void rejects_closed() {
    stub("CLOSED", null);
    assertThatThrownBy(() -> checker.check(oneLeg("2.0000")))
        .isInstanceOf(MarketClosedException.class);
  }

  @Test
  @DisplayName("rejects when the market status key is missing")
  void rejects_missing_market() {
    stub(null, null);
    assertThatThrownBy(() -> checker.check(oneLeg("2.0000")))
        .isInstanceOf(MarketClosedException.class);
  }

  @Test
  @DisplayName("rejects when the selection is no longer priced")
  void rejects_missing_odds() {
    stub("OPEN", null);
    assertThatThrownBy(() -> checker.check(oneLeg("2.0000")))
        .isInstanceOf(MarketClosedException.class)
        .hasMessageContaining("no longer priced");
  }
}
