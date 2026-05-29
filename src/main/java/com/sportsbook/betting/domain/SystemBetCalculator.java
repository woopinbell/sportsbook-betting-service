package com.sportsbook.betting.domain;

import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.protocol.value.Odds;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

/**
 * System (K-of-N) combination expansion + payout maths (ADR-0008).
 *
 * <p>Stake convention: {@code Bet.stake} is the <b>per-line (unit) stake</b>. A slip is a set of
 * lines, each an accumulator that pays {@code unit * product(odds on that line)} when all its
 * selections win:
 *
 * <ul>
 *   <li><b>Single</b> — 1 line of 1 selection.
 *   <li><b>Multiple</b> — 1 line of all N selections.
 *   <li><b>System(K-of-N)</b> — C(N, K) lines, one per K-subset of the selections ("each
 *       combination multiplies the stake", ADR-0008).
 * </ul>
 *
 * So the total amount committed (and debited from the wallet) is {@code unit * lineCount}, and the
 * worst-case payout (all selections win, so every line wins) is {@code unit * Σ line products}.
 * Single/Multiple fall out of the same formula with {@code lineCount == 1}.
 *
 * <p>The C(N, K) line count is bounded by the L4 max-selections rule (default 15); the worst case
 * C(15, 7) = 6435 lines is small enough to enumerate eagerly.
 */
@Component
public class SystemBetCalculator {

  /** Number of lines (accumulators) the slip expands into. */
  public int lineCount(BetSlipType type, int legCount) {
    if (type instanceof BetSlipType.System system) {
      return Math.toIntExact(binomial(legCount, system.minWins()));
    }
    return 1;
  }

  /** Total amount committed = per-line stake × line count (the wallet debit amount, ADR-0017). */
  public Money totalStake(BetSlipType type, Money unitStake, int legCount) {
    return unitStake.multiply(lineCount(type, legCount));
  }

  /**
   * Worst-case payout: every line wins. {@code unit × Σ(product of each line's odds)}, floored to
   * whole minor units (conservative — the real payout is decided at settlement). No division, so
   * there is a single rounding step.
   */
  public Money maxPayout(BetSlipType type, Money unitStake, List<Odds> legOdds) {
    BigDecimal summedLineProducts = BigDecimal.ZERO;
    for (List<Integer> line : lines(type, legOdds.size())) {
      summedLineProducts = summedLineProducts.add(lineProduct(line, legOdds));
    }
    long amount =
        BigDecimal.valueOf(unitStake.amount())
            .multiply(summedLineProducts)
            .setScale(0, RoundingMode.FLOOR)
            .longValueExact();
    return new Money(amount, unitStake.currency());
  }

  private static List<List<Integer>> lines(BetSlipType type, int legCount) {
    if (type instanceof BetSlipType.System system) {
      return combinations(legCount, system.minWins());
    }
    // Single (legCount == 1) and Multiple (legCount == N) are a single line over all selections.
    return List.of(IntStream.range(0, legCount).boxed().toList());
  }

  private static BigDecimal lineProduct(List<Integer> line, List<Odds> legOdds) {
    BigDecimal product = BigDecimal.ONE;
    for (int index : line) {
      product = product.multiply(legOdds.get(index).decimal());
    }
    return product;
  }

  /**
   * All {@code k}-subsets of {@code [0, n)}, each in ascending order, in lexicographic order.
   * Package-visible (and pure) so the combinatorics can be asserted directly.
   */
  static List<List<Integer>> combinations(int n, int k) {
    List<List<Integer>> out = new ArrayList<>();
    collect(0, n, k, new ArrayList<>(), out);
    return out;
  }

  private static void collect(
      int start, int n, int k, List<Integer> current, List<List<Integer>> out) {
    if (current.size() == k) {
      out.add(new ArrayList<>(current));
      return;
    }
    // Prune: stop when too few remaining elements to reach size k.
    for (int i = start; i <= n - (k - current.size()); i++) {
      current.add(i);
      collect(i + 1, n, k, current, out);
      current.remove(current.size() - 1);
    }
  }

  /** C(n, k) via the multiplicative formula; exact for the L4-bounded range (n ≤ 15). */
  static long binomial(int n, int k) {
    if (k < 0 || k > n) {
      return 0;
    }
    int kk = Math.min(k, n - k);
    long result = 1;
    for (int i = 0; i < kk; i++) {
      result = result * (n - i) / (i + 1);
    }
    return result;
  }
}
