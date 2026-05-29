package com.sportsbook.betting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.protocol.value.Odds;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Combinatorics + payout maths for {@link SystemBetCalculator} — the interview-critical path. */
class SystemBetCalculatorTest {

  private final SystemBetCalculator calc = new SystemBetCalculator();

  private static Money krw(long minor) {
    return new Money(minor, Currency.KRW);
  }

  private static List<Odds> odds(String... decimals) {
    return java.util.Arrays.stream(decimals).map(Odds::ofDecimal).toList();
  }

  @Nested
  @DisplayName("binomial coefficient C(n, k)")
  class Binomial {

    @Test
    void known_values() {
      assertThat(SystemBetCalculator.binomial(3, 2)).isEqualTo(3);
      assertThat(SystemBetCalculator.binomial(4, 2)).isEqualTo(6);
      assertThat(SystemBetCalculator.binomial(5, 3)).isEqualTo(10);
      assertThat(SystemBetCalculator.binomial(15, 7)).isEqualTo(6435); // worst case under L4=15
    }

    @Test
    void edges() {
      assertThat(SystemBetCalculator.binomial(5, 0)).isEqualTo(1);
      assertThat(SystemBetCalculator.binomial(5, 5)).isEqualTo(1);
      assertThat(SystemBetCalculator.binomial(5, 6)).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("combination enumeration")
  class Combinations {

    @Test
    void two_of_three_enumerates_exactly() {
      assertThat(SystemBetCalculator.combinations(3, 2))
          .containsExactly(List.of(0, 1), List.of(0, 2), List.of(1, 2));
    }

    @Test
    void counts_match_binomial_and_stay_well_formed() {
      List<List<Integer>> c = SystemBetCalculator.combinations(5, 3);
      assertThat(c).hasSize(10);
      assertThat(c)
          .allSatisfy(combo -> assertThat(combo).hasSize(3).isSorted().doesNotHaveDuplicates());
      assertThat(c).doesNotHaveDuplicates();
    }

    @Test
    void handles_the_largest_allowed_explosion() {
      assertThat(SystemBetCalculator.combinations(15, 7)).hasSize(6435);
    }
  }

  @Nested
  @DisplayName("lineCount + totalStake")
  class LinesAndStake {

    @Test
    void single_and_multiple_are_one_line() {
      assertThat(calc.lineCount(new BetSlipType.Single(), 1)).isEqualTo(1);
      assertThat(calc.lineCount(new BetSlipType.Multiple(), 3)).isEqualTo(1);
    }

    @Test
    void system_line_count_is_c_n_k() {
      assertThat(calc.lineCount(new BetSlipType.System(2, 3), 3)).isEqualTo(3);
      assertThat(calc.lineCount(new BetSlipType.System(3, 5), 5)).isEqualTo(10);
    }

    @Test
    void total_stake_multiplies_unit_by_line_count() {
      assertThat(calc.totalStake(new BetSlipType.Single(), krw(10_000), 1)).isEqualTo(krw(10_000));
      assertThat(calc.totalStake(new BetSlipType.Multiple(), krw(10_000), 3))
          .isEqualTo(krw(10_000));
      // 2-of-3 = 3 lines, so 10,000 per line commits 30,000.
      assertThat(calc.totalStake(new BetSlipType.System(2, 3), krw(10_000), 3))
          .isEqualTo(krw(30_000));
    }
  }

  @Nested
  @DisplayName("max payout")
  class MaxPayout {

    @Test
    void single_is_stake_times_odds() {
      assertThat(calc.maxPayout(new BetSlipType.Single(), krw(10_000), odds("2.5000")))
          .isEqualTo(krw(25_000));
    }

    @Test
    void multiple_is_stake_times_product() {
      assertThat(calc.maxPayout(new BetSlipType.Multiple(), krw(10_000), odds("2.0000", "3.0000")))
          .isEqualTo(krw(60_000));
      assertThat(
              calc.maxPayout(
                  new BetSlipType.Multiple(), krw(10_000), odds("2.0000", "3.0000", "4.0000")))
          .isEqualTo(krw(240_000));
    }

    @Test
    void system_2_of_3_sums_each_combination_product() {
      // combos {0,1}=6, {0,2}=8, {1,2}=12 -> sum 26 -> 10,000 * 26
      assertThat(
              calc.maxPayout(
                  new BetSlipType.System(2, 3), krw(10_000), odds("2.0000", "3.0000", "4.0000")))
          .isEqualTo(krw(260_000));
    }

    @Test
    void system_2_of_4_all_equal_odds() {
      // C(4,2)=6 combos, each 2.0*2.0=4 -> sum 24 -> 1,000 * 24
      assertThat(
              calc.maxPayout(
                  new BetSlipType.System(2, 4),
                  krw(1_000),
                  odds("2.0000", "2.0000", "2.0000", "2.0000")))
          .isEqualTo(krw(24_000));
    }

    @Test
    void system_k_equals_n_matches_a_multiple() {
      Money asSystem =
          calc.maxPayout(
              new BetSlipType.System(3, 3), krw(10_000), odds("2.0000", "3.0000", "4.0000"));
      Money asMultiple =
          calc.maxPayout(
              new BetSlipType.Multiple(), krw(10_000), odds("2.0000", "3.0000", "4.0000"));
      assertThat(asSystem).isEqualTo(asMultiple).isEqualTo(krw(240_000));
    }

    @Test
    void floors_sub_unit_fractions() {
      // 1.0150 * 1.0150 = 1.03022500; 1000 * 1.030225 = 1030.225 -> floor 1030
      assertThat(calc.maxPayout(new BetSlipType.Multiple(), krw(1_000), odds("1.0150", "1.0150")))
          .isEqualTo(krw(1_030));
    }

    @Test
    void preserves_currency() {
      Money usd =
          calc.maxPayout(new BetSlipType.Single(), new Money(500L, Currency.USD), odds("2.0000"));
      assertThat(usd.currency()).isEqualTo(Currency.USD);
      assertThat(usd.amount()).isEqualTo(1_000L);
    }
  }
}
