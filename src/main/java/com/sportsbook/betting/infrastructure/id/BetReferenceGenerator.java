package com.sportsbook.betting.infrastructure.id;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * Generates the human-readable bet reference {@code B-YYYY-MM-DD-XXXXXXXX} (ADR-0003): a UTC date
 * segment plus an 8-char base36 suffix. Shown to users / CS instead of the internal UUID v7.
 *
 * <p>Uses {@link ThreadLocalRandom} (not {@code SecureRandom}) — the reference is not a secret, and
 * under the 10k-concurrent target a shared {@code SecureRandom} would be a contention point.
 * Uniqueness is ultimately guaranteed by the {@code uk_bet_reference} DB constraint; the 8-char
 * suffix (36^8 ≈ 2.8e12 per day) makes a collision-retry vanishingly rare.
 */
@Component
public class BetReferenceGenerator {

  private static final int SUFFIX_LENGTH = 8;
  private static final char[] BASE36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

  public String next(Instant at) {
    StringBuilder reference = new StringBuilder("B-").append(DATE.format(at)).append('-');
    ThreadLocalRandom random = ThreadLocalRandom.current();
    for (int i = 0; i < SUFFIX_LENGTH; i++) {
      reference.append(BASE36[random.nextInt(BASE36.length)]);
    }
    return reference.toString();
  }
}
