package com.sportsbook.betting.persistence;

import com.sportsbook.betting.domain.Bet;
import com.sportsbook.protocol.domain.BetStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence port for the {@link Bet} aggregate. Cursor-pagination and stale-PENDING scan queries
 * are added in later tasks (REST history API / reconciliation job); this declares only what the
 * domain + placement flow needs first.
 */
public interface BetRepository extends JpaRepository<Bet, UUID> {

  /**
   * Idempotency lookup (ADR-0005): the prior bet for a replayed Idempotency-Key. Legs are fetched
   * eagerly because a replay is rendered straight back to the caller as a response.
   */
  @EntityGraph(attributePaths = "legs")
  Optional<Bet> findByIdempotencyKey(String idempotencyKey);

  /** Loads a bet with its legs eagerly so callers can read the slip outside an open session. */
  @EntityGraph(attributePaths = "legs")
  Optional<Bet> findWithLegsByBetId(UUID betId);

  /**
   * Stale bets in a given status, legs eagerly fetched — the reconciliation scan for PENDING bets
   * older than the timeout (ADR-0017 compensation). Backed by ix_bet_status_created.
   */
  @EntityGraph(attributePaths = "legs")
  List<Bet> findByStatusAndCreatedAtBefore(BetStatus status, Instant threshold, Pageable pageable);

  /**
   * Cursor page (ADR-0004): a user's bets newest-first. betId is UUID v7 (time-ordered), so it
   * doubles as the keyset cursor — backed by ix_bet_user_bet. First page omits the cursor.
   */
  @EntityGraph(attributePaths = "legs")
  List<Bet> findByUserIdOrderByBetIdDesc(UUID userId, Pageable pageable);

  @EntityGraph(attributePaths = "legs")
  List<Bet> findByUserIdAndBetIdLessThanOrderByBetIdDesc(
      UUID userId, UUID cursor, Pageable pageable);
}
