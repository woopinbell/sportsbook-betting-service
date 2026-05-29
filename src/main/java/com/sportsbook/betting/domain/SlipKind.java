package com.sportsbook.betting.domain;

import com.sportsbook.protocol.domain.BetSlipType;

/**
 * STI discriminator for the {@code bet.slip_type} column (ADR-0008). This is the flat, persistable
 * projection of the {@link BetSlipType} sealed interface: {@code SYSTEM} additionally needs {@code
 * system_min_wins} / {@code system_total_selections}, while {@code SINGLE} / {@code MULTIPLE} leave
 * those columns null. The rich {@link BetSlipType} (with its K-of-N parameters) is reconstructed
 * via {@link Bet#slipType()} and decomposed via {@link #of(BetSlipType)}.
 */
public enum SlipKind {
  SINGLE,
  MULTIPLE,
  SYSTEM;

  /** Maps a rich {@link BetSlipType} to its persistable discriminator. */
  public static SlipKind of(BetSlipType slipType) {
    if (slipType instanceof BetSlipType.Single) {
      return SINGLE;
    }
    if (slipType instanceof BetSlipType.Multiple) {
      return MULTIPLE;
    }
    if (slipType instanceof BetSlipType.System) {
      return SYSTEM;
    }
    throw new IllegalArgumentException("Unknown BetSlipType: " + slipType);
  }
}
