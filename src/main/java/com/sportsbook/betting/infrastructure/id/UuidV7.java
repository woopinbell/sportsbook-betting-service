package com.sportsbook.betting.infrastructure.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Minimal UUID v7 generator (RFC 9562 §5.7). Each consuming service rolls its own generator rather
 * than importing yet-another-uuid-library (shared-protocol CLAUDE.md "EventId" comment).
 *
 * <p>Layout:
 *
 * <ul>
 *   <li>48 bits — Unix timestamp in milliseconds, big-endian
 *   <li>4 bits — version, fixed to {@code 0x7}
 *   <li>12 bits — random
 *   <li>2 bits — variant, fixed to {@code 0b10}
 *   <li>62 bits — random
 * </ul>
 *
 * <p>Time-ordered prefix gives B-tree indexes a friendly insert pattern (sequential leaf pages),
 * which is the main reason we use v7 instead of v4 for primary keys (ADR-0003).
 */
public final class UuidV7 {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final long TIMESTAMP_MASK_48BIT = 0xFFFF_FFFF_FFFFL;
  private static final int TIMESTAMP_LEFT_SHIFT = 16;
  private static final long VERSION_7_MASK = 0x7000L;
  private static final long VARIANT_SET_MASK = 0x8000_0000_0000_0000L;
  private static final long VARIANT_CLEAR_MASK = 0x3FFF_FFFF_FFFF_FFFFL;
  private static final int RANDOM_BITS_IN_MSB = 0x1000;

  public static UUID generate() {
    return generate(System.currentTimeMillis());
  }

  // Visible for testing — letting the caller supply the timestamp keeps the deterministic
  // ordering assertions hermetic.
  static UUID generate(long unixMillis) {
    long msb = (unixMillis & TIMESTAMP_MASK_48BIT) << TIMESTAMP_LEFT_SHIFT;
    msb |= VERSION_7_MASK;
    msb |= RANDOM.nextInt(RANDOM_BITS_IN_MSB);

    long lsb = RANDOM.nextLong();
    lsb &= VARIANT_CLEAR_MASK;
    lsb |= VARIANT_SET_MASK;

    return new UUID(msb, lsb);
  }

  private UuidV7() {
    // Utility holder.
  }
}
