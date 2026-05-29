package com.sportsbook.betting.validation;

import com.sportsbook.betting.domain.BetLeg;
import com.sportsbook.betting.error.ValidationFailedException;
import com.sportsbook.betting.policy.BettingPolicyProperties;
import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.value.Money;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Structural slip validation against the configured policy (ADR-0008 / ADR-0009). Runs before any
 * external call on the placement path (ADR-0017 step 2), so a malformed slip is rejected cheaply
 * without touching risk, wallet, or the DB.
 *
 * <ul>
 *   <li><b>L1 Same Market</b> — two legs on the same market can't both win; rejected for multis.
 *   <li><b>L2 Same Event</b> — two legs on the same event are correlated; rejected for multis.
 *   <li><b>L4 Max Selections</b> — leg count must not exceed {@code max-selections}.
 *   <li><b>L5 Max Total Odds</b> — the product of the submitted odds must not exceed {@code
 *       max-total-odds}.
 * </ul>
 *
 * <p>L1 takes precedence over L2 (a shared market implies a shared event) because the market check
 * runs first in the single pass. Stake bounds (ADR-0009) are a separate entry point used by the
 * placement flow alongside structural validation.
 */
@Component
public class BetSlipValidator {

  private final BettingPolicyProperties policy;

  public BetSlipValidator(BettingPolicyProperties policy) {
    this.policy = policy;
  }

  /** Validates leg count (L4), per-leg uniqueness (L1/L2 for multis), and total odds (L5). */
  public void validate(BetSlipType slipType, List<BetLeg> legs) {
    if (legs.isEmpty()) {
      throw new ValidationFailedException("Slip must contain at least one selection");
    }
    if (legs.size() > policy.maxSelections()) {
      throw new ValidationFailedException(
          "L4 max selections exceeded: " + legs.size() + " > " + policy.maxSelections());
    }
    if (isMulti(slipType)) {
      checkDistinctMarketsAndEvents(legs);
    }
    checkTotalOdds(legs);
  }

  /** Validates the stake is within the per-currency bounds (ADR-0009). */
  public void validateStake(Money stake) {
    Long min = policy.minStake().get(stake.currency());
    Long max = policy.maxStake().get(stake.currency());
    if (min == null || max == null) {
      throw new ValidationFailedException("Unsupported stake currency: " + stake.currency());
    }
    if (stake.amount() < min) {
      throw new ValidationFailedException(
          "Stake below minimum: " + stake.amount() + " < " + min + " " + stake.currency());
    }
    if (stake.amount() > max) {
      throw new ValidationFailedException(
          "Stake above maximum: " + stake.amount() + " > " + max + " " + stake.currency());
    }
  }

  private static boolean isMulti(BetSlipType slipType) {
    return !(slipType instanceof BetSlipType.Single);
  }

  private static void checkDistinctMarketsAndEvents(List<BetLeg> legs) {
    Set<UUID> markets = new HashSet<>();
    Set<UUID> events = new HashSet<>();
    for (BetLeg leg : legs) {
      // Market checked first so a shared market reports as L1, not the weaker L2.
      if (!markets.add(leg.marketId())) {
        throw new ValidationFailedException(
            "L1 same market not allowed in a multi: market " + leg.marketId());
      }
      if (!events.add(leg.eventId())) {
        throw new ValidationFailedException(
            "L2 same event not allowed in a multi: event " + leg.eventId());
      }
    }
  }

  private void checkTotalOdds(List<BetLeg> legs) {
    BigDecimal product = BigDecimal.ONE;
    for (BetLeg leg : legs) {
      product = product.multiply(leg.oddsAtSubmission().decimal());
    }
    if (product.compareTo(policy.maxTotalOdds()) > 0) {
      throw new ValidationFailedException(
          "L5 max total odds exceeded: "
              + product.stripTrailingZeros().toPlainString()
              + " > "
              + policy.maxTotalOdds().toPlainString());
    }
  }
}
