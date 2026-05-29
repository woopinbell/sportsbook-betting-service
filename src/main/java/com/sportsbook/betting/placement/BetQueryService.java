package com.sportsbook.betting.placement;

import com.sportsbook.betting.api.CursorPage;
import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.error.BetNotFoundException;
import com.sportsbook.betting.persistence.BetRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the bet API (ADR-0004): single-bet lookup and the cursor-paginated history. Legs are
 * fetched eagerly by the repository queries so the controller can render selections after the read
 * transaction closes. Returns domain {@link Bet}s; the controller owns the wire mapping.
 */
@Service
public class BetQueryService {

  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;

  private final BetRepository bets;

  public BetQueryService(BetRepository bets) {
    this.bets = bets;
  }

  @Transactional(readOnly = true)
  public Bet byId(UUID betId) {
    return bets.findWithLegsByBetId(betId)
        .orElseThrow(() -> new BetNotFoundException("No bet with id " + betId));
  }

  /**
   * One keyset page of a user's bets, newest first. Fetches {@code limit + 1} rows to decide {@code
   * hasMore} without a count query; {@code nextCursor} is the last betId of the page.
   */
  @Transactional(readOnly = true)
  public CursorPage<Bet> page(UUID userId, UUID cursor, Integer requestedLimit) {
    int limit = clampLimit(requestedLimit);
    PageRequest probe = PageRequest.of(0, limit + 1);
    List<Bet> rows =
        cursor == null
            ? bets.findByUserIdOrderByBetIdDesc(userId, probe)
            : bets.findByUserIdAndBetIdLessThanOrderByBetIdDesc(userId, cursor, probe);

    boolean hasMore = rows.size() > limit;
    List<Bet> items = hasMore ? rows.subList(0, limit) : rows;
    String nextCursor =
        hasMore && !items.isEmpty() ? items.get(items.size() - 1).betId().toString() : null;
    return new CursorPage<>(items, nextCursor, hasMore);
  }

  private static int clampLimit(Integer requestedLimit) {
    if (requestedLimit == null || requestedLimit <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(requestedLimit, MAX_LIMIT);
  }
}
