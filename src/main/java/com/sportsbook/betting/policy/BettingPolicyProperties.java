package com.sportsbook.betting.policy;

import com.sportsbook.protocol.value.Currency;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Betting slip policy, bound from {@code betting.policy.*} (ADR-0009: application.yml in V1, DB
 * config when operational hot-reload is needed). Type-safe constructor binding means a malformed
 * value fails startup rather than surfacing at request time.
 *
 * <p>The compact constructor supplies the ADR-0008 / ADR-0009 defaults so a missing key degrades to
 * a sane value instead of {@code 0}/{@code null}: max 15 selections, max total odds 10 000, 3 %
 * slippage tolerance, and per-currency stake bounds.
 */
@ConfigurationProperties(prefix = "betting.policy")
public record BettingPolicyProperties(
    int maxSelections,
    BigDecimal maxTotalOdds,
    Map<Currency, Long> minStake,
    Map<Currency, Long> maxStake,
    BigDecimal slippageTolerancePercent) {

  private static final int DEFAULT_MAX_SELECTIONS = 15;
  private static final BigDecimal DEFAULT_MAX_TOTAL_ODDS = new BigDecimal("10000");
  private static final BigDecimal DEFAULT_SLIPPAGE_PERCENT = new BigDecimal("3");
  private static final long DEFAULT_MIN_KRW = 1_000L;
  private static final long DEFAULT_MIN_USD = 100L;
  private static final long DEFAULT_MAX_KRW = 1_000_000L;
  private static final long DEFAULT_MAX_USD = 1_000L;

  public BettingPolicyProperties {
    if (maxSelections <= 0) {
      maxSelections = DEFAULT_MAX_SELECTIONS;
    }
    if (maxTotalOdds == null) {
      maxTotalOdds = DEFAULT_MAX_TOTAL_ODDS;
    }
    if (slippageTolerancePercent == null) {
      slippageTolerancePercent = DEFAULT_SLIPPAGE_PERCENT;
    }
    if (minStake == null || minStake.isEmpty()) {
      minStake = Map.of(Currency.KRW, DEFAULT_MIN_KRW, Currency.USD, DEFAULT_MIN_USD);
    }
    if (maxStake == null || maxStake.isEmpty()) {
      maxStake = Map.of(Currency.KRW, DEFAULT_MAX_KRW, Currency.USD, DEFAULT_MAX_USD);
    }
  }
}
