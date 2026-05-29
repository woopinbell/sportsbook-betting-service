package com.sportsbook.betting.placement;

import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.protocol.value.Odds;
import java.util.List;
import java.util.UUID;

/**
 * Input to {@link BetPlacementService#place(PlaceBetCommand)}: a validated-at-the-edge placement
 * request. {@code unitStake} is the per-line stake (ADR-0008); the orchestration derives total
 * stake (the wallet debit) and max payout from it. The REST layer maps its DTO into this command.
 */
public record PlaceBetCommand(
    UUID userId,
    BetSlipType slipType,
    List<SelectionInput> selections,
    Money unitStake,
    IdempotencyKey idempotencyKey) {

  /** One selection the user picked, with the odds shown at submit time. */
  public record SelectionInput(
      UUID eventId, UUID marketId, UUID selectionId, Odds oddsAtSubmission) {}
}
