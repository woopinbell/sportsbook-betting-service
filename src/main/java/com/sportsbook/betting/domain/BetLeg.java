package com.sportsbook.betting.domain;

import com.sportsbook.betting.infrastructure.id.UuidV7;
import com.sportsbook.protocol.value.Odds;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * One selection on a bet slip — the child side of the {@link Bet} aggregate. A leg records what the
 * user picked ({@code eventId} / {@code marketId} / {@code selectionId}) and the decimal odds shown
 * at submit time ({@code oddsAtSubmission}); the slippage check (ADR-0008) compares that against
 * the live odds in the odds-feed cache before acceptance.
 *
 * <p>{@code legIndex} preserves submission order so reconstructing the slip (and the System K-of-N
 * combinations) is deterministic. Legs carry no {@link com.sportsbook.protocol.value.Money} — stake
 * and payout live on the parent {@link Bet}.
 */
@Entity
@Table(name = "bet_leg")
public class BetLeg {

  @Id
  @Column(name = "leg_id", nullable = false, updatable = false)
  private UUID legId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "bet_id", nullable = false)
  private Bet bet;

  @Column(name = "leg_index", nullable = false)
  private int legIndex;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "market_id", nullable = false)
  private UUID marketId;

  @Column(name = "selection_id", nullable = false)
  private UUID selectionId;

  @Column(name = "odds_at_submission", nullable = false, precision = 9, scale = 4)
  private BigDecimal oddsAtSubmission;

  protected BetLeg() {
    // Required by JPA.
  }

  private BetLeg(UUID eventId, UUID marketId, UUID selectionId, Odds oddsAtSubmission) {
    this.legId = UuidV7.generate();
    this.eventId = Objects.requireNonNull(eventId, "eventId");
    this.marketId = Objects.requireNonNull(marketId, "marketId");
    this.selectionId = Objects.requireNonNull(selectionId, "selectionId");
    this.oddsAtSubmission = Objects.requireNonNull(oddsAtSubmission, "oddsAtSubmission").decimal();
  }

  /**
   * Creates a detached leg. The parent {@link Bet} sets the back-reference and {@link #legIndex}
   * when the leg is attached, so the leg is only ever persisted as part of its aggregate.
   */
  public static BetLeg create(
      UUID eventId, UUID marketId, UUID selectionId, Odds oddsAtSubmission) {
    return new BetLeg(eventId, marketId, selectionId, oddsAtSubmission);
  }

  // Package-private: only Bet may wire the back-reference + order, keeping the aggregate
  // consistent.
  void assignTo(Bet bet, int legIndex) {
    this.bet = Objects.requireNonNull(bet, "bet");
    this.legIndex = legIndex;
  }

  public UUID legId() {
    return legId;
  }

  public int legIndex() {
    return legIndex;
  }

  public UUID eventId() {
    return eventId;
  }

  public UUID marketId() {
    return marketId;
  }

  public UUID selectionId() {
    return selectionId;
  }

  public Odds oddsAtSubmission() {
    return Odds.ofDecimal(oddsAtSubmission);
  }
}
