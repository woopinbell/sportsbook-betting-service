package com.sportsbook.betting.validation;

import com.sportsbook.betting.domain.BetLeg;
import com.sportsbook.betting.error.MarketClosedException;
import com.sportsbook.betting.error.OddsDriftException;
import com.sportsbook.betting.policy.BettingPolicyProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Validates each leg against the live odds-feed cache (ADR-0008 slippage tolerance, default 3 %).
 * Read-only consumer of the odds-feed-service Redis keys (this repo never writes them):
 *
 * <ul>
 *   <li>{@code market:{eventId}:{marketId}} — market status name; must be {@code OPEN}.
 *   <li>{@code odds:{eventId}:{marketId}:{selectionId}} — current decimal odds (string).
 * </ul>
 *
 * <p>The drift rule is user-protective: a slip is accepted while the live odds are at least {@code
 * submitted * (1 - tolerance)}. Odds that improved (drifted up) always pass; only a drop beyond
 * tolerance rejects. The comparison is done by cross-multiplication to avoid any rounding in the
 * tolerance division.
 */
@Component
public class OddsSlippageChecker {

  private static final String MARKET_OPEN = "OPEN";
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final StringRedisTemplate redis;
  private final BettingPolicyProperties policy;

  public OddsSlippageChecker(StringRedisTemplate redis, BettingPolicyProperties policy) {
    this.redis = redis;
    this.policy = policy;
  }

  public void check(List<BetLeg> legs) {
    BigDecimal toleranceFactor = HUNDRED.subtract(policy.slippageTolerancePercent());
    for (BetLeg leg : legs) {
      requireMarketOpen(leg);
      requireWithinTolerance(leg, toleranceFactor);
    }
  }

  private void requireMarketOpen(BetLeg leg) {
    String status = redis.opsForValue().get(marketKey(leg.eventId(), leg.marketId()));
    if (!MARKET_OPEN.equals(status)) {
      throw new MarketClosedException(
          "Market not open for betting (status="
              + status
              + "): event "
              + leg.eventId()
              + " market "
              + leg.marketId());
    }
  }

  private void requireWithinTolerance(BetLeg leg, BigDecimal toleranceFactor) {
    String currentRaw =
        redis.opsForValue().get(oddsKey(leg.eventId(), leg.marketId(), leg.selectionId()));
    if (currentRaw == null) {
      throw new MarketClosedException("Selection no longer priced: selection " + leg.selectionId());
    }
    BigDecimal current = new BigDecimal(currentRaw);
    BigDecimal submitted = leg.oddsAtSubmission().decimal();
    // Accept iff current >= submitted * (100 - tolerance) / 100, rearranged to avoid division:
    //   current * 100 >= submitted * (100 - tolerance)
    BigDecimal currentScaled = current.multiply(HUNDRED);
    BigDecimal floor = submitted.multiply(toleranceFactor);
    if (currentScaled.compareTo(floor) < 0) {
      throw new OddsDriftException(
          "Odds drifted beyond tolerance: submitted "
              + submitted.toPlainString()
              + ", current "
              + current.toPlainString()
              + ", tolerance "
              + policy.slippageTolerancePercent().toPlainString()
              + "%");
    }
  }

  private static String marketKey(UUID eventId, UUID marketId) {
    return "market:" + eventId + ":" + marketId;
  }

  private static String oddsKey(UUID eventId, UUID marketId, UUID selectionId) {
    return "odds:" + eventId + ":" + marketId + ":" + selectionId;
  }
}
