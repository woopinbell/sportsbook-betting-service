package com.sportsbook.betting.settlement;

import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.persistence.BetRepository;
import com.sportsbook.protocol.domain.BetStatus;
import com.sportsbook.protocol.domain.SettlementResult;
import com.sportsbook.protocol.value.Money;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies async settlement outcomes onto the {@link Bet} aggregate (ADR-0006). Each method is one
 * short transaction — load the bet, apply the terminal transition, let JPA dirty checking flush.
 *
 * <p>Idempotent by {@code betId}: a redelivered event whose bet already reached the target terminal
 * state is a no-op, so Kafka's at-least-once delivery is safe. A genuinely illegal input — a
 * settlement for an unknown bet, or a transition from a non-{@code ACCEPTED} state — throws,
 * routing the record to the listener's retry / dead-letter path rather than silently corrupting
 * state.
 */
@Component
public class BetSettlementService {

  private static final Logger log = LoggerFactory.getLogger(BetSettlementService.class);

  private final BetRepository bets;

  public BetSettlementService(BetRepository bets) {
    this.bets = bets;
  }

  /**
   * Settles an {@code ACCEPTED} bet (ADR-0013): records the result and the actual payout, status →
   * {@code SETTLED}. No-op if already settled (idempotent replay).
   */
  @Transactional
  public void applySettled(UUID betId, SettlementResult result, Money payout, Instant settledAt) {
    Bet bet =
        bets.findById(betId)
            .orElseThrow(() -> new IllegalStateException("BetSettled for unknown betId " + betId));
    if (bet.status() == BetStatus.SETTLED) {
      log.debug("BetSettled replay for {} — already SETTLED, skip", betId);
      return;
    }
    bet.settle(result, payout, settledAt);
    log.info("Bet {} settled: result={} payout={}", betId, result, payout);
  }
}
