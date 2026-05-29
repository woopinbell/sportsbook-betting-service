package com.sportsbook.betting.infrastructure.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UuidV7Test {

  @Test
  @DisplayName("version nibble = 7 and variant bits = 10")
  void version_and_variant_bits() {
    UUID id = UuidV7.generate();

    assertThat(id.version()).isEqualTo(7);
    assertThat(id.variant()).isEqualTo(2); // RFC 9562 variant 10 → java.util.UUID#variant() == 2
  }

  @Test
  @DisplayName("MSB orders strictly by supplied timestamp — B-tree-friendly inserts")
  void time_ordered_msb() {
    long t0 = 1_700_000_000_000L;
    long t1 = t0 + 1L;
    long t2 = t0 + 100L;

    UUID u0 = UuidV7.generate(t0);
    UUID u1 = UuidV7.generate(t1);
    UUID u2 = UuidV7.generate(t2);

    // We compare on the 48-bit timestamp prefix only; the trailing 16 random bits
    // mean the MSB is not strictly monotonic at the bit level when timestamps tie.
    assertThat(u0.getMostSignificantBits() >>> 16).isLessThan(u1.getMostSignificantBits() >>> 16);
    assertThat(u1.getMostSignificantBits() >>> 16).isLessThan(u2.getMostSignificantBits() >>> 16);
  }

  @Test
  @DisplayName("collisions vanishingly improbable across a thousand calls")
  void uniqueness_at_scale() {
    Set<UUID> ids = new HashSet<>();
    for (int i = 0; i < 1_000; i++) {
      ids.add(UuidV7.generate());
    }
    assertThat(ids).hasSize(1_000);
  }
}
