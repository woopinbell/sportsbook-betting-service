package com.sportsbook.betting.placement;

import com.sportsbook.protocol.value.IdempotencyKey;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis fast-path for idempotent placement (ADR-0005). The {@code uk_bet_idempotency_key} DB
 * constraint is the strong "process once" guarantee; this SETNX reservation is a latency shortcut
 * that lets the orchestration skip the persist + risk + wallet work when a duplicate is already
 * in-flight.
 *
 * <p>Key format {@code idempotency:betting:<caller-key>}, 24h TTL. The reservation holds {@code
 * "1"} until the bet is accepted, then is overwritten with the betId for traceability.
 */
@Component
public class IdempotencyCache {

  static final Duration TTL = Duration.ofHours(24);
  static final String KEY_PREFIX = "idempotency:betting:";
  private static final String RESERVED = "1";

  private final StringRedisTemplate redis;

  public IdempotencyCache(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /** SETNX: returns true if this caller reserved the key, false if it was already taken. */
  public boolean tryReserve(IdempotencyKey key) {
    Boolean reserved = redis.opsForValue().setIfAbsent(redisKey(key), RESERVED, TTL);
    return Boolean.TRUE.equals(reserved);
  }

  /** Records the committed betId so the fast path can correlate a later retry. */
  public void markProcessed(IdempotencyKey key, UUID betId) {
    redis.opsForValue().set(redisKey(key), betId.toString(), TTL);
  }

  private static String redisKey(IdempotencyKey key) {
    return KEY_PREFIX + key.value();
  }
}
