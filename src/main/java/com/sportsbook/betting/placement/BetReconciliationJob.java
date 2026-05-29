package com.sportsbook.betting.placement;

import com.sportsbook.betting.client.WalletClient;
import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.domain.SystemBetCalculator;
import com.sportsbook.betting.error.DependencyUnavailableException;
import com.sportsbook.betting.error.InsufficientBalanceException;
import com.sportsbook.betting.outbox.BetEventFactory;
import com.sportsbook.betting.persistence.BetRepository;
import com.sportsbook.protocol.domain.BetStatus;
import com.sportsbook.protocol.value.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Closes the ADR-0017 partial-failure window: a bet whose wallet debit succeeded but whose accept
 * transaction was lost (process crash, DB blip) is left stuck in PENDING. This job finds PENDING
 * bets older than {@code betting.reconciliation.pending-timeout} and resolves each by re-attempting
 * the wallet debit under the same {@code betId} idempotency key:
 *
 * <ul>
 *   <li>debit succeeds → the funds are confirmed held (debited now or earlier) → <b>roll
 *       forward</b> to ACCEPTED + emit BetPlacedRequested;
 *   <li>insufficient balance → the debit never landed and cannot → <b>roll back</b> to REJECTED.
 * </ul>
 *
 * <p>The re-debit is the status probe and the roll-forward in one — wallet has no "lookup by key"
 * endpoint, and the operation is idempotent so it never double-charges. There is deliberately no
 * compensating credit on the roll-back path: when the debit never landed, nothing was taken, and
 * wallet's {@code locked} bucket is per-account (not per-bet), so refunding a never-debited stake
 * would wrongly draw down another bet's held funds. The credit/refund primitive ({@link
 * WalletClient#refund}) is reserved for confirmed-debit abandon flows (settlement / admin).
 */
@Component
public class BetReconciliationJob {

  private static final Logger log = LoggerFactory.getLogger(BetReconciliationJob.class);
  static final int BATCH_SIZE = 100;

  private final BetRepository bets;
  private final BetStore store;
  private final WalletClient walletClient;
  private final BetEventFactory eventFactory;
  private final SystemBetCalculator calculator;
  private final Clock clock;
  private final Duration pendingTimeout;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public BetReconciliationJob(
      BetRepository bets,
      BetStore store,
      WalletClient walletClient,
      BetEventFactory eventFactory,
      SystemBetCalculator calculator,
      Clock clock,
      @Value("${betting.reconciliation.pending-timeout:30s}") Duration pendingTimeout) {
    this.bets = bets;
    this.store = store;
    this.walletClient = walletClient;
    this.eventFactory = eventFactory;
    this.calculator = calculator;
    this.clock = clock;
    this.pendingTimeout = pendingTimeout;
  }

  @org.springframework.scheduling.annotation.Scheduled(
      fixedDelayString = "${betting.reconciliation.poll-interval-ms:10000}")
  public void reconcile() {
    Instant threshold = clock.instant().minus(pendingTimeout);
    List<Bet> stale =
        bets.findByStatusAndCreatedAtBefore(
            BetStatus.PENDING, threshold, PageRequest.of(0, BATCH_SIZE));
    for (Bet bet : stale) {
      reconcileOne(bet);
    }
  }

  private void reconcileOne(Bet bet) {
    Money totalStake = calculator.totalStake(bet.slipType(), bet.stake(), bet.legs().size());
    try {
      // Idempotent re-debit: confirms the stake is held (debited now or before).
      walletClient.debit(bet.betId(), bet.userId(), totalStake);
      store.acceptAndEnqueue(
          bet.betId(), eventFactory.placedRequested(bet, clock.instant()), clock.instant());
      log.info("Reconciliation rolled bet {} forward to ACCEPTED", bet.betId());
    } catch (InsufficientBalanceException notDebited) {
      store.markRejected(bet.betId(), "RECONCILED_NO_DEBIT", clock.instant());
      log.info("Reconciliation rolled bet {} back to REJECTED (debit never landed)", bet.betId());
    } catch (DependencyUnavailableException unavailable) {
      log.warn(
          "Reconciliation deferred bet {} — wallet unavailable: {}",
          bet.betId(),
          unavailable.getMessage());
    }
  }
}
