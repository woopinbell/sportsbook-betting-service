package com.sportsbook.betting.domain;

import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.domain.BetStatus;
import com.sportsbook.protocol.domain.SettlementResult;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Bet slip aggregate root — STI model (ADR-0008): one {@code bet} table with a {@code slip_type}
 * discriminator, plus a child {@code bet_leg} per selection. A slip is created {@code PENDING},
 * then the synchronous placement orchestration (ADR-0017) drives it to {@code ACCEPTED} (risk +
 * wallet both succeeded) or {@code REJECTED} (validation / risk / wallet declined). Post-acceptance
 * transitions ({@code SETTLED} / {@code CANCELLED} / {@code VOIDED}) are owned by other services.
 *
 * <p>The rich {@link BetSlipType} is decomposed into the flat {@link SlipKind} + nullable
 * system-parameter columns for persistence, and rebuilt by {@link #slipType()}. {@code maxPayout}
 * is computed at creation by a domain service (System K-of-N expansion) and stored for audit; the
 * real payout is decided at settlement.
 */
@Entity
@Table(name = "bet")
public class Bet {

  private static final int MULTIPLE_MIN_LEGS = 2;
  private static final int STATUS_COLUMN_LENGTH = 16;
  private static final int SETTLEMENT_RESULT_COLUMN_LENGTH = 8;

  @Id
  @Column(name = "bet_id", nullable = false, updatable = false)
  private UUID betId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "bet_reference", nullable = false, updatable = false, length = 32)
  private String betReference;

  @Enumerated(EnumType.STRING)
  @Column(name = "slip_type", nullable = false, updatable = false, length = STATUS_COLUMN_LENGTH)
  private SlipKind slipType;

  @Column(name = "system_min_wins")
  private Integer systemMinWins;

  @Column(name = "system_total_selections")
  private Integer systemTotalSelections;

  @Embedded
  @AttributeOverrides({
    @AttributeOverride(name = "amount", column = @Column(name = "stake_amount", nullable = false)),
    @AttributeOverride(
        name = "currency",
        column = @Column(name = "stake_currency", nullable = false, length = 3))
  })
  private EmbeddedMoney stake;

  @Embedded
  @AttributeOverrides({
    @AttributeOverride(
        name = "amount",
        column = @Column(name = "max_payout_amount", nullable = false)),
    @AttributeOverride(
        name = "currency",
        column = @Column(name = "max_payout_currency", nullable = false, length = 3))
  })
  private EmbeddedMoney maxPayout;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = STATUS_COLUMN_LENGTH)
  private BetStatus status;

  @Column(name = "rejection_reason", length = 64)
  private String rejectionReason;

  // Settlement outcome (ADR-0006 / ADR-0013), null until the bet reaches a terminal state.
  @Enumerated(EnumType.STRING)
  @Column(name = "settlement_result", length = SETTLEMENT_RESULT_COLUMN_LENGTH)
  private SettlementResult settlementResult;

  @Embedded
  @AttributeOverrides({
    @AttributeOverride(name = "amount", column = @Column(name = "settled_payout_amount")),
    @AttributeOverride(
        name = "currency",
        column = @Column(name = "settled_payout_currency", length = 3))
  })
  private EmbeddedMoney settledPayout;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
  private String idempotencyKey;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @OneToMany(mappedBy = "bet", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("legIndex ASC")
  private List<BetLeg> legs = new ArrayList<>();

  protected Bet() {
    // Required by JPA.
  }

  // 9 params — under the Checkstyle ParameterNumber max (10). A creation command record would hide
  // the validation in a less obvious place; the aggregate root is the right home for these checks.
  private Bet(
      UUID betId,
      UUID userId,
      String betReference,
      BetSlipType slipType,
      Money stake,
      Money maxPayout,
      IdempotencyKey idempotencyKey,
      List<BetLeg> legs,
      Instant now) {
    this.betId = Objects.requireNonNull(betId, "betId");
    this.userId = Objects.requireNonNull(userId, "userId");
    this.betReference = requireText(betReference, "betReference");
    Objects.requireNonNull(slipType, "slipType");
    Objects.requireNonNull(stake, "stake");
    Objects.requireNonNull(maxPayout, "maxPayout");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(legs, "legs");
    Objects.requireNonNull(now, "now");

    requireSameCurrency(stake, maxPayout);
    requireStructure(slipType, legs.size());

    this.slipType = SlipKind.of(slipType);
    if (slipType instanceof BetSlipType.System system) {
      this.systemMinWins = system.minWins();
      this.systemTotalSelections = system.totalSelections();
    }
    this.stake = EmbeddedMoney.of(stake);
    this.maxPayout = EmbeddedMoney.of(maxPayout);
    this.idempotencyKey = idempotencyKey.value();
    this.status = BetStatus.PENDING;
    this.createdAt = now;
    this.updatedAt = now;
    attachLegs(legs);
  }

  /**
   * Creates a slip in {@code PENDING} state with its legs attached. Validates currency consistency
   * (stake vs. maxPayout) and structural shape (SINGLE = 1 leg, MULTIPLE ≥ 2 legs, SYSTEM total =
   * leg count). The L1/L2/L4/L5 policy rules (ADR-0008) are checked earlier by the slip validator;
   * this enforces the invariants the aggregate itself must never violate.
   */
  public static Bet pending(
      UUID betId,
      UUID userId,
      String betReference,
      BetSlipType slipType,
      Money stake,
      Money maxPayout,
      IdempotencyKey idempotencyKey,
      List<BetLeg> legs,
      Instant now) {
    return new Bet(
        betId, userId, betReference, slipType, stake, maxPayout, idempotencyKey, legs, now);
  }

  /** PENDING → ACCEPTED: risk and wallet both succeeded (ADR-0017). */
  public void accept(Instant now) {
    requireStatus(BetStatus.PENDING);
    this.status = BetStatus.ACCEPTED;
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  /** PENDING → REJECTED: validation, risk, or wallet declined. Terminal, pre-acceptance. */
  public void reject(String reason, Instant now) {
    requireStatus(BetStatus.PENDING);
    this.status = BetStatus.REJECTED;
    this.rejectionReason = reason;
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  /**
   * ACCEPTED → SETTLED: settlement-service adjudicated the bet after the match result (ADR-0006).
   * Records the {@link SettlementResult} and the actual payout credited to the wallet — distinct
   * from the worst-case {@link #maxPayout()} stored at acceptance. The payout shares the slip
   * currency (no FX inside a slip, V1).
   */
  public void settle(SettlementResult result, Money payout, Instant now) {
    requireStatus(BetStatus.ACCEPTED);
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(payout, "payout");
    Objects.requireNonNull(now, "now");
    if (payout.currency() != stake.currency()) {
      throw new IllegalArgumentException(
          "Payout currency " + payout.currency() + " does not match stake " + stake.currency());
    }
    this.status = BetStatus.SETTLED;
    this.settlementResult = result;
    this.settledPayout = EmbeddedMoney.of(payout);
    this.resolvedAt = now;
    this.updatedAt = now;
  }

  /** Rebuilds the rich slip type, including the K-of-N parameters for SYSTEM slips. */
  public BetSlipType slipType() {
    return switch (slipType) {
      case SINGLE -> new BetSlipType.Single();
      case MULTIPLE -> new BetSlipType.Multiple();
      case SYSTEM -> new BetSlipType.System(systemMinWins, systemTotalSelections);
    };
  }

  private void attachLegs(List<BetLeg> incoming) {
    for (int i = 0; i < incoming.size(); i++) {
      BetLeg leg = incoming.get(i);
      leg.assignTo(this, i);
      this.legs.add(leg);
    }
  }

  private void requireStatus(BetStatus expected) {
    if (this.status != expected) {
      throw new IllegalStateException(
          "Illegal transition: expected status " + expected + " but was " + this.status);
    }
  }

  private static void requireStructure(BetSlipType slipType, int legCount) {
    if (slipType instanceof BetSlipType.Single && legCount != 1) {
      throw new IllegalArgumentException("SINGLE slip must have exactly 1 leg, got " + legCount);
    }
    if (slipType instanceof BetSlipType.Multiple && legCount < MULTIPLE_MIN_LEGS) {
      throw new IllegalArgumentException(
          "MULTIPLE slip must have at least " + MULTIPLE_MIN_LEGS + " legs, got " + legCount);
    }
    if (slipType instanceof BetSlipType.System system && system.totalSelections() != legCount) {
      throw new IllegalArgumentException(
          "SYSTEM totalSelections ("
              + system.totalSelections()
              + ") must equal leg count ("
              + legCount
              + ")");
    }
  }

  private static void requireSameCurrency(Money stake, Money maxPayout) {
    if (stake.currency() != maxPayout.currency()) {
      throw new IllegalArgumentException(
          "Currency mismatch: stake " + stake.currency() + " vs maxPayout " + maxPayout.currency());
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  public UUID betId() {
    return betId;
  }

  public UUID userId() {
    return userId;
  }

  public String betReference() {
    return betReference;
  }

  public Money stake() {
    return stake.toMoney();
  }

  public Money maxPayout() {
    return maxPayout.toMoney();
  }

  public BetStatus status() {
    return status;
  }

  public String rejectionReason() {
    return rejectionReason;
  }

  public SettlementResult settlementResult() {
    return settlementResult;
  }

  public Money settledPayout() {
    return settledPayout == null ? null : settledPayout.toMoney();
  }

  public Instant resolvedAt() {
    return resolvedAt;
  }

  public String idempotencyKey() {
    return idempotencyKey;
  }

  public long version() {
    return version;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public List<BetLeg> legs() {
    return List.copyOf(legs);
  }
}
