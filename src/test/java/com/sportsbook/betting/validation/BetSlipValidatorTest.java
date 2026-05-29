package com.sportsbook.betting.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sportsbook.betting.domain.BetLeg;
import com.sportsbook.betting.error.ValidationFailedException;
import com.sportsbook.betting.infrastructure.id.UuidV7;
import com.sportsbook.betting.policy.BettingPolicyProperties;
import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.protocol.value.Odds;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BetSlipValidatorTest {

  private static final UUID E1 = UuidV7.generate();
  private static final UUID E2 = UuidV7.generate();
  private static final UUID E3 = UuidV7.generate();
  private static final UUID M1 = UuidV7.generate();
  private static final UUID M2 = UuidV7.generate();
  private static final UUID M3 = UuidV7.generate();

  private static BettingPolicyProperties policy(int maxSelections, String maxTotalOdds) {
    return new BettingPolicyProperties(
        maxSelections,
        new BigDecimal(maxTotalOdds),
        Map.of(Currency.KRW, 1_000L, Currency.USD, 100L),
        Map.of(Currency.KRW, 1_000_000L, Currency.USD, 1_000L),
        new BigDecimal("3"));
  }

  private static final BetSlipValidator DEFAULT = new BetSlipValidator(policy(15, "10000"));

  private static BetLeg leg(UUID event, UUID market, String odds) {
    return BetLeg.create(event, market, UuidV7.generate(), Odds.ofDecimal(odds));
  }

  @Nested
  @DisplayName("L1 / L2 — same market / same event in a multi")
  class DistinctnessRules {

    @Test
    void rejects_two_legs_on_the_same_market() {
      List<BetLeg> legs = List.of(leg(E1, M1, "1.9000"), leg(E1, M1, "2.1000"));

      assertThatThrownBy(() -> DEFAULT.validate(new BetSlipType.Multiple(), legs))
          .isInstanceOf(ValidationFailedException.class)
          .hasMessageContaining("L1 same market");
    }

    @Test
    void rejects_two_legs_on_the_same_event_different_market() {
      List<BetLeg> legs = List.of(leg(E1, M1, "1.9000"), leg(E1, M2, "2.1000"));

      assertThatThrownBy(() -> DEFAULT.validate(new BetSlipType.Multiple(), legs))
          .isInstanceOf(ValidationFailedException.class)
          .hasMessageContaining("L2 same event");
    }

    @Test
    void allows_distinct_events_and_markets() {
      List<BetLeg> legs =
          List.of(leg(E1, M1, "1.9000"), leg(E2, M2, "2.1000"), leg(E3, M3, "1.5000"));

      assertThatCode(() -> DEFAULT.validate(new BetSlipType.Multiple(), legs))
          .doesNotThrowAnyException();
    }

    @Test
    void same_event_is_fine_on_a_single() {
      // A SINGLE slip has one leg; L1/L2 simply do not apply.
      assertThatCode(
              () -> DEFAULT.validate(new BetSlipType.Single(), List.of(leg(E1, M1, "1.9000"))))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("L4 — max selections")
  class MaxSelections {

    @Test
    void rejects_more_legs_than_allowed() {
      BetSlipValidator validator = new BetSlipValidator(policy(2, "10000"));
      List<BetLeg> legs = List.of(leg(E1, M1, "1.5"), leg(E2, M2, "1.5"), leg(E3, M3, "1.5"));

      assertThatThrownBy(() -> validator.validate(new BetSlipType.Multiple(), legs))
          .isInstanceOf(ValidationFailedException.class)
          .hasMessageContaining("L4 max selections");
    }

    @Test
    void allows_leg_count_at_the_limit() {
      BetSlipValidator validator = new BetSlipValidator(policy(2, "10000"));
      List<BetLeg> legs = List.of(leg(E1, M1, "1.5"), leg(E2, M2, "1.5"));

      assertThatCode(() -> validator.validate(new BetSlipType.Multiple(), legs))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("L5 — max total odds")
  class MaxTotalOdds {

    @Test
    void rejects_product_over_the_cap() {
      BetSlipValidator validator = new BetSlipValidator(policy(15, "10"));
      List<BetLeg> legs = List.of(leg(E1, M1, "4.0000"), leg(E2, M2, "4.0000")); // product 16

      assertThatThrownBy(() -> validator.validate(new BetSlipType.Multiple(), legs))
          .isInstanceOf(ValidationFailedException.class)
          .hasMessageContaining("L5 max total odds");
    }

    @Test
    void allows_product_under_the_cap() {
      BetSlipValidator validator = new BetSlipValidator(policy(15, "10"));
      List<BetLeg> legs = List.of(leg(E1, M1, "2.0000"), leg(E2, M2, "2.0000")); // product 4

      assertThatCode(() -> validator.validate(new BetSlipType.Multiple(), legs))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("stake bounds (ADR-0009)")
  class StakeBounds {

    @Test
    void rejects_stake_below_minimum() {
      assertThatThrownBy(() -> DEFAULT.validateStake(new Money(500L, Currency.KRW)))
          .isInstanceOf(ValidationFailedException.class)
          .hasMessageContaining("below minimum");
    }

    @Test
    void rejects_stake_above_maximum() {
      assertThatThrownBy(() -> DEFAULT.validateStake(new Money(2_000_000L, Currency.KRW)))
          .isInstanceOf(ValidationFailedException.class)
          .hasMessageContaining("above maximum");
    }

    @Test
    void allows_stake_within_bounds() {
      assertThatCode(() -> DEFAULT.validateStake(new Money(10_000L, Currency.KRW)))
          .doesNotThrowAnyException();
    }

    @Test
    void rejects_unsupported_currency() {
      BetSlipValidator krwOnly =
          new BetSlipValidator(
              new BettingPolicyProperties(
                  15,
                  new BigDecimal("10000"),
                  Map.of(Currency.KRW, 1_000L),
                  Map.of(Currency.KRW, 1_000_000L),
                  new BigDecimal("3")));

      assertThatThrownBy(() -> krwOnly.validateStake(new Money(500L, Currency.USD)))
          .isInstanceOf(ValidationFailedException.class)
          .hasMessageContaining("Unsupported stake currency");
    }
  }
}
