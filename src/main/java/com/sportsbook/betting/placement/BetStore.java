package com.sportsbook.betting.placement;

import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.outbox.OutboxEvent;
import com.sportsbook.betting.outbox.OutboxEventRepository;
import com.sportsbook.betting.persistence.BetRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional units of the placement flow, kept in their own bean so {@link
 * BetPlacementService} can call them through the Spring proxy (self-invocation would bypass
 * {@code @Transactional}) and, crucially, so no external HTTP call ever runs inside a DB
 * transaction (ADR-0017 — don't hold a pooled connection across a network round-trip).
 *
 * <p>Each method is one short transaction: persist PENDING, mark REJECTED, or accept + enqueue the
 * outbox row atomically.
 */
@Component
public class BetStore {

  private final BetRepository bets;
  private final OutboxEventRepository outbox;

  public BetStore(BetRepository bets, OutboxEventRepository outbox) {
    this.bets = bets;
    this.outbox = outbox;
  }

  @Transactional(readOnly = true)
  public Optional<Bet> findByIdempotencyKey(String idempotencyKey) {
    return bets.findByIdempotencyKey(idempotencyKey);
  }

  /**
   * Persists the slip as PENDING. {@code saveAndFlush} surfaces a duplicate-key violation here and
   * now (the strong idempotency guard, ADR-0005) rather than at a later flush.
   */
  @Transactional
  public void savePending(Bet bet) {
    bets.saveAndFlush(bet);
  }

  /** PENDING -> REJECTED after a risk or wallet decline. No-op if the bet is already gone. */
  @Transactional
  public void markRejected(UUID betId, String reason, Instant now) {
    bets.findById(betId).ifPresent(bet -> bet.reject(reason, now));
  }

  /**
   * PENDING -> ACCEPTED and the BetPlacedRequested outbox row, in one transaction — the atomic step
   * that makes the event inseparable from the acceptance (ADR-0006 / ADR-0017 step 7).
   */
  @Transactional
  public Bet acceptAndEnqueue(UUID betId, OutboxEvent event, Instant now) {
    Bet bet =
        bets.findById(betId)
            .orElseThrow(
                () -> new IllegalStateException("Bet vanished before acceptance: " + betId));
    bet.accept(now);
    outbox.save(event);
    return bet;
  }
}
