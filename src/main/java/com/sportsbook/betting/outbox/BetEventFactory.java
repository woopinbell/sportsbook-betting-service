package com.sportsbook.betting.outbox;

import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.domain.BetLeg;
import com.sportsbook.betting.infrastructure.id.UuidV7;
import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.event.BetPlacedRequested;
import com.sportsbook.protocol.event.BetSlipTypeTag;
import com.sportsbook.protocol.event.RequestedSelection;
import com.sportsbook.protocol.value.Money;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Builds the {@code BetPlacedRequested} outbox row from an accepted {@link Bet} (ADR-0006). Keeps
 * the Avro construction out of the placement service.
 *
 * <p>Partition key is the user UUID (not eventId): a slip can span several events, and the
 * downstream consumers — risk-service's per-user sliding window and the settlement read model —
 * benefit from per-user ordering / co-location. ADR-0006's eventId rule targets per-match event
 * streams (odds, settlement), not a per-user placement action.
 */
@Component
public class BetEventFactory {

  static final String TOPIC = "bet.placed.v1";
  static final String SCHEMA = "BetPlacedRequested";

  public OutboxEvent placedRequested(Bet bet, Instant now) {
    BetPlacedRequested record =
        BetPlacedRequested.newBuilder()
            .setBetId(bet.betId().toString())
            .setUserId(bet.userId().toString())
            .setSlipType(toTag(bet.slipType()))
            .setSystemMinWins(systemMinWins(bet.slipType()))
            .setSystemTotalSelections(systemTotalSelections(bet.slipType()))
            .setSelections(toSelections(bet.legs()))
            .setStake(toAvroMoney(bet.stake()))
            .setIdempotencyKey(bet.idempotencyKey())
            .setRequestedAt(bet.createdAt())
            .build();
    byte[] payload = AvroSerializer.serialize(record);
    return OutboxEvent.pending(
        UuidV7.generate(), TOPIC, bet.userId().toString(), SCHEMA, payload, now);
  }

  private static BetSlipTypeTag toTag(BetSlipType slipType) {
    if (slipType instanceof BetSlipType.Single) {
      return BetSlipTypeTag.SINGLE;
    }
    if (slipType instanceof BetSlipType.Multiple) {
      return BetSlipTypeTag.MULTIPLE;
    }
    return BetSlipTypeTag.SYSTEM;
  }

  private static Integer systemMinWins(BetSlipType slipType) {
    return slipType instanceof BetSlipType.System system ? system.minWins() : null;
  }

  private static Integer systemTotalSelections(BetSlipType slipType) {
    return slipType instanceof BetSlipType.System system ? system.totalSelections() : null;
  }

  private static List<RequestedSelection> toSelections(List<BetLeg> legs) {
    return legs.stream()
        .map(
            leg ->
                RequestedSelection.newBuilder()
                    .setEventId(leg.eventId().toString())
                    .setMarketId(leg.marketId().toString())
                    .setSelectionId(leg.selectionId().toString())
                    .setOddsAtSubmission(leg.oddsAtSubmission().decimal().toPlainString())
                    .build())
        .toList();
  }

  private static com.sportsbook.protocol.event.Money toAvroMoney(Money money) {
    return com.sportsbook.protocol.event.Money.newBuilder()
        .setAmount(money.amount())
        .setCurrency(money.currency().name())
        .build();
  }
}
