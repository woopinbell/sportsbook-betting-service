package com.sportsbook.betting.placement;

import com.sportsbook.betting.client.RiskClient;
import com.sportsbook.betting.client.WalletClient;
import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.domain.BetLeg;
import com.sportsbook.betting.domain.SystemBetCalculator;
import com.sportsbook.betting.error.BetPlacementException;
import com.sportsbook.betting.error.DuplicateBetException;
import com.sportsbook.betting.infrastructure.id.BetReferenceGenerator;
import com.sportsbook.betting.infrastructure.id.UuidV7;
import com.sportsbook.betting.outbox.BetEventFactory;
import com.sportsbook.betting.outbox.OutboxEvent;
import com.sportsbook.betting.placement.PlaceBetCommand.SelectionInput;
import com.sportsbook.betting.validation.BetSlipValidator;
import com.sportsbook.betting.validation.OddsSlippageChecker;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.protocol.value.Odds;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Synchronous bet-placement orchestration (ADR-0017). The orchestrator itself is NOT transactional:
 * it runs short DB transactions through {@link BetStore} and the external risk/wallet HTTP calls
 * strictly between them, so a pooled DB connection is never held across a network round-trip.
 *
 * <pre>
 *   1. idempotency replay     — committed bet for this key? return it.
 *   2. validate slip + stake  — structural rules (ADR-0008), before any side effect.
 *   3. odds slippage          — against the odds-feed cache (3% tolerance).
 *   4. reserve + persist      — SETNX fast path + PENDING row (DB unique = strong guard).
 *   5. risk check (HTTP)      — decline -> REJECTED, rethrow.
 *   6. wallet debit (HTTP)    — decline -> REJECTED, rethrow. betId is the wallet idempotency key.
 *   7. accept + outbox        — one transaction; emits BetPlacedRequested.
 * </pre>
 *
 * <p>Any {@link BetPlacementException} (validation, slippage, risk, wallet, duplicate) is the
 * accept/reject verdict surfaced to the caller; the partial-failure window (wallet debited but the
 * accept transaction lost) is closed by the reconciliation job, not here.
 */
@Service
public class BetPlacementService {

  private final BetSlipValidator validator;
  private final OddsSlippageChecker slippageChecker;
  private final SystemBetCalculator calculator;
  private final RiskClient riskClient;
  private final WalletClient walletClient;
  private final BetStore store;
  private final IdempotencyCache idempotency;
  private final BetReferenceGenerator referenceGenerator;
  private final BetEventFactory eventFactory;
  private final Clock clock;

  // Ten collaborators — exactly the Checkstyle ParameterNumber limit; this is the composition root
  // of the flow, so the wiring is inherent rather than incidental.
  @SuppressWarnings("checkstyle:ParameterNumber")
  public BetPlacementService(
      BetSlipValidator validator,
      OddsSlippageChecker slippageChecker,
      SystemBetCalculator calculator,
      RiskClient riskClient,
      WalletClient walletClient,
      BetStore store,
      IdempotencyCache idempotency,
      BetReferenceGenerator referenceGenerator,
      BetEventFactory eventFactory,
      Clock clock) {
    this.validator = validator;
    this.slippageChecker = slippageChecker;
    this.calculator = calculator;
    this.riskClient = riskClient;
    this.walletClient = walletClient;
    this.store = store;
    this.idempotency = idempotency;
    this.referenceGenerator = referenceGenerator;
    this.eventFactory = eventFactory;
    this.clock = clock;
  }

  /**
   * Accepts or rejects a slip, returning the persisted {@link Bet} (ACCEPTED, or the existing bet
   * on an idempotent replay). Throws a {@link BetPlacementException} when the slip is rejected.
   */
  public Bet place(PlaceBetCommand command) {
    String key = command.idempotencyKey().value();

    // 1. Idempotent replay: the DB is authoritative for an already-committed bet.
    Optional<Bet> committed = store.findByIdempotencyKey(key);
    if (committed.isPresent()) {
      return committed.get();
    }

    // 2-3. Validate before any side effect, so a malformed slip burns no idempotency key / row.
    List<BetLeg> legs = toLegs(command.selections());
    validator.validate(command.slipType(), legs);
    validator.validateStake(command.unitStake());
    slippageChecker.check(legs);

    Money totalStake = calculator.totalStake(command.slipType(), command.unitStake(), legs.size());
    Money maxPayout = calculator.maxPayout(command.slipType(), command.unitStake(), oddsOf(legs));

    // 4. Reserve (SETNX fast path) then persist PENDING (DB unique = strong guard, ADR-0005).
    if (!idempotency.tryReserve(command.idempotencyKey())) {
      return store
          .findByIdempotencyKey(key)
          .orElseThrow(
              () ->
                  new DuplicateBetException("A bet with this Idempotency-Key is being processed"));
    }

    UUID betId = UuidV7.generate();
    Instant now = clock.instant();
    Bet bet =
        Bet.pending(
            betId,
            command.userId(),
            referenceGenerator.next(now),
            command.slipType(),
            command.unitStake(),
            maxPayout,
            command.idempotencyKey(),
            legs,
            now);
    try {
      store.savePending(bet);
    } catch (DataIntegrityViolationException duplicate) {
      // A concurrent request won the race and committed first; return its bet.
      return store.findByIdempotencyKey(key).orElseThrow(() -> duplicate);
    }

    // 5-6. Synchronous risk then wallet — outside any DB transaction. betId is the wallet
    // idempotency key so the debit is safe to retry / reconcile.
    try {
      riskClient.check(betId, command.userId(), totalStake, selectionIds(legs));
      walletClient.debit(betId, command.userId(), totalStake);
    } catch (BetPlacementException rejection) {
      store.markRejected(betId, rejection.errorCode().name(), clock.instant());
      throw rejection;
    }

    // 7. Accept + enqueue BetPlacedRequested atomically.
    OutboxEvent event = eventFactory.placedRequested(bet, clock.instant());
    Bet accepted = store.acceptAndEnqueue(betId, event, clock.instant());
    idempotency.markProcessed(command.idempotencyKey(), betId);
    return accepted;
  }

  private static List<BetLeg> toLegs(List<SelectionInput> selections) {
    return selections.stream()
        .map(s -> BetLeg.create(s.eventId(), s.marketId(), s.selectionId(), s.oddsAtSubmission()))
        .toList();
  }

  private static List<Odds> oddsOf(List<BetLeg> legs) {
    return legs.stream().map(BetLeg::oddsAtSubmission).toList();
  }

  private static List<String> selectionIds(List<BetLeg> legs) {
    return legs.stream().map(leg -> leg.selectionId().toString()).toList();
  }
}
