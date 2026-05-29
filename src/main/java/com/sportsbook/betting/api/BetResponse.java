package com.sportsbook.betting.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.domain.BetLeg;
import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.value.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire view of a {@link Bet} (ADR-0004). {@code rejectionReason} is present only for a REJECTED
 * bet, so {@code @JsonInclude(NON_NULL)} trims it otherwise.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BetResponse(
    UUID betId,
    String betReference,
    UUID userId,
    SlipTypeView slipType,
    String status,
    Money stake,
    Money maxPayout,
    List<SelectionView> selections,
    String rejectionReason,
    Instant createdAt) {

  /** Tagged slip type; minWins / totalSelections are present only for SYSTEM. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SlipTypeView(String type, Integer minWins, Integer totalSelections) {}

  public record SelectionView(
      UUID eventId, UUID marketId, UUID selectionId, String oddsAtSubmission) {}

  public static BetResponse from(Bet bet) {
    return new BetResponse(
        bet.betId(),
        bet.betReference(),
        bet.userId(),
        slipTypeView(bet.slipType()),
        bet.status().name(),
        bet.stake(),
        bet.maxPayout(),
        bet.legs().stream().map(BetResponse::selectionView).toList(),
        bet.rejectionReason(),
        bet.createdAt());
  }

  private static SlipTypeView slipTypeView(BetSlipType slipType) {
    if (slipType instanceof BetSlipType.System system) {
      return new SlipTypeView("SYSTEM", system.minWins(), system.totalSelections());
    }
    String type = slipType instanceof BetSlipType.Single ? "SINGLE" : "MULTIPLE";
    return new SlipTypeView(type, null, null);
  }

  private static SelectionView selectionView(BetLeg leg) {
    return new SelectionView(
        leg.eventId(),
        leg.marketId(),
        leg.selectionId(),
        leg.oddsAtSubmission().decimal().toPlainString());
  }
}
