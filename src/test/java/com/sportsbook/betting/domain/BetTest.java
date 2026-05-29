package com.sportsbook.betting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sportsbook.betting.infrastructure.id.UuidV7;
import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.domain.BetStatus;
import com.sportsbook.protocol.domain.SettlementResult;
import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.protocol.value.Odds;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Pure invariant coverage for the {@link Bet} aggregate — no persistence. */
class BetTest {

  private static final UUID USER = UUID.fromString("00000000-0000-7000-8000-00000000aaaa");
  private static final Instant T0 = Instant.parse("2026-05-29T07:00:00Z");
  private static final Instant T1 = Instant.parse("2026-05-29T07:00:01Z");
  private static final Instant T2 = Instant.parse("2026-05-29T09:30:00Z");
  private static final IdempotencyKey IDEM = IdempotencyKey.of("idem-key-1");

  private static Money krw(long minor) {
    return new Money(minor, Currency.KRW);
  }

  private static BetLeg leg() {
    return BetLeg.create(
        UuidV7.generate(), UuidV7.generate(), UuidV7.generate(), Odds.ofDecimal("1.8500"));
  }

  private static List<BetLeg> legs(int n) {
    return IntStream.range(0, n).mapToObj(i -> leg()).toList();
  }

  private static Bet pending(BetSlipType type, List<BetLeg> legs) {
    return Bet.pending(
        UuidV7.generate(),
        USER,
        "B-2026-05-29-AAAA0001",
        type,
        krw(10_000),
        krw(25_000),
        IDEM,
        legs,
        T0);
  }

  @Nested
  @DisplayName("structural shape per slip type")
  class Structure {

    @Test
    void single_requires_exactly_one_leg() {
      assertThat(pending(new BetSlipType.Single(), legs(1)).status()).isEqualTo(BetStatus.PENDING);
      assertThatThrownBy(() -> pending(new BetSlipType.Single(), legs(2)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("SINGLE");
    }

    @Test
    void multiple_requires_at_least_two_legs() {
      assertThat(pending(new BetSlipType.Multiple(), legs(2)).legs()).hasSize(2);
      assertThatThrownBy(() -> pending(new BetSlipType.Multiple(), legs(1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("MULTIPLE");
    }

    @Test
    void system_total_selections_must_equal_leg_count() {
      assertThat(pending(new BetSlipType.System(2, 3), legs(3)).legs()).hasSize(3);
      assertThatThrownBy(() -> pending(new BetSlipType.System(2, 3), legs(2)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("SYSTEM");
    }
  }

  @Nested
  @DisplayName("System K-of-N parameter bounds (enforced by BetSlipType, ADR-0008)")
  class SystemBounds {

    @Test
    void min_wins_must_be_within_one_to_total() {
      assertThatThrownBy(() -> new BetSlipType.System(0, 3))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new BetSlipType.System(4, 3))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void total_selections_must_be_within_two_to_fifteen() {
      assertThatThrownBy(() -> new BetSlipType.System(1, 1))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new BetSlipType.System(1, 16))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  @DisplayName("rejects stake / maxPayout currency mismatch")
  void rejects_currency_mismatch() {
    assertThatThrownBy(
            () ->
                Bet.pending(
                    UuidV7.generate(),
                    USER,
                    "B-2026-05-29-AAAA0002",
                    new BetSlipType.Single(),
                    krw(10_000),
                    new Money(25_000, Currency.USD),
                    IDEM,
                    legs(1),
                    T0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Currency mismatch");
  }

  @Nested
  @DisplayName("slip type round-trips through STI columns")
  class SlipTypeRoundTrip {

    @Test
    void system_keeps_k_of_n_parameters() {
      Bet bet = pending(new BetSlipType.System(2, 3), legs(3));

      assertThat(bet.slipType())
          .isInstanceOf(BetSlipType.System.class)
          .isEqualTo(new BetSlipType.System(2, 3));
    }

    @Test
    void single_and_multiple_carry_no_system_parameters() {
      assertThat(pending(new BetSlipType.Single(), legs(1)).slipType())
          .isInstanceOf(BetSlipType.Single.class);
      assertThat(pending(new BetSlipType.Multiple(), legs(3)).slipType())
          .isInstanceOf(BetSlipType.Multiple.class);
    }
  }

  @Test
  @DisplayName("legs are attached in submission order with sequential indexes")
  void legs_ordered() {
    Bet bet = pending(new BetSlipType.Multiple(), legs(4));

    assertThat(bet.legs()).extracting(BetLeg::legIndex).containsExactly(0, 1, 2, 3);
  }

  @Nested
  @DisplayName("lifecycle transitions from PENDING")
  class Transitions {

    @Test
    void accept_moves_to_accepted() {
      Bet bet = pending(new BetSlipType.Single(), legs(1));

      bet.accept(T1);

      assertThat(bet.status()).isEqualTo(BetStatus.ACCEPTED);
      assertThat(bet.updatedAt()).isEqualTo(T1);
    }

    @Test
    void reject_moves_to_rejected_with_reason() {
      Bet bet = pending(new BetSlipType.Single(), legs(1));

      bet.reject("ODDS_DRIFT", T1);

      assertThat(bet.status()).isEqualTo(BetStatus.REJECTED);
      assertThat(bet.rejectionReason()).isEqualTo("ODDS_DRIFT");
    }

    @Test
    void cannot_accept_twice() {
      Bet bet = pending(new BetSlipType.Single(), legs(1));
      bet.accept(T1);

      assertThatThrownBy(() -> bet.accept(T1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannot_reject_after_accept() {
      Bet bet = pending(new BetSlipType.Single(), legs(1));
      bet.accept(T1);

      assertThatThrownBy(() -> bet.reject("late", T1)).isInstanceOf(IllegalStateException.class);
    }
  }

  @Nested
  @DisplayName("settlement transitions from ACCEPTED")
  class SettlementTransitions {

    private Bet accepted() {
      Bet bet = pending(new BetSlipType.Single(), legs(1));
      bet.accept(T1);
      return bet;
    }

    @Test
    void settle_records_result_and_payout() {
      Bet bet = accepted();

      bet.settle(SettlementResult.WON, krw(18_500), T2);

      assertThat(bet.status()).isEqualTo(BetStatus.SETTLED);
      assertThat(bet.settlementResult()).isEqualTo(SettlementResult.WON);
      assertThat(bet.settledPayout()).isEqualTo(krw(18_500));
      assertThat(bet.resolvedAt()).isEqualTo(T2);
    }

    @Test
    void lost_settles_with_zero_payout() {
      Bet bet = accepted();

      bet.settle(SettlementResult.LOST, krw(0), T2);

      assertThat(bet.status()).isEqualTo(BetStatus.SETTLED);
      assertThat(bet.settledPayout()).isEqualTo(krw(0));
    }

    @Test
    void void_records_reason() {
      Bet bet = accepted();

      bet.voidBet(VoidReason.EVENT_CANCELLED, T2);

      assertThat(bet.status()).isEqualTo(BetStatus.VOIDED);
      assertThat(bet.voidReason()).isEqualTo(VoidReason.EVENT_CANCELLED);
      assertThat(bet.resolvedAt()).isEqualTo(T2);
    }

    @Test
    void cannot_settle_before_accept() {
      Bet bet = pending(new BetSlipType.Single(), legs(1));

      assertThatThrownBy(() -> bet.settle(SettlementResult.WON, krw(18_500), T2))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannot_void_before_accept() {
      Bet bet = pending(new BetSlipType.Single(), legs(1));

      assertThatThrownBy(() -> bet.voidBet(VoidReason.EVENT_CANCELLED, T2))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannot_settle_twice() {
      Bet bet = accepted();
      bet.settle(SettlementResult.WON, krw(18_500), T2);

      assertThatThrownBy(() -> bet.settle(SettlementResult.LOST, krw(0), T2))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannot_void_after_settle() {
      Bet bet = accepted();
      bet.settle(SettlementResult.WON, krw(18_500), T2);

      assertThatThrownBy(() -> bet.voidBet(VoidReason.ADMIN_VOID, T2))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void settle_rejects_payout_currency_mismatch() {
      Bet bet = accepted();

      assertThatThrownBy(
              () -> bet.settle(SettlementResult.WON, new Money(18_500, Currency.USD), T2))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Payout currency");
    }
  }
}
