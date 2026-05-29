package com.sportsbook.betting.error;

import com.sportsbook.protocol.error.ErrorCode;

/**
 * A selection's market is not open for betting — suspended, closed, or no longer priced in the
 * odds-feed cache. Maps to {@link ErrorCode#EVENT_CLOSED} (HTTP 422).
 */
public class MarketClosedException extends BetPlacementException {

  public MarketClosedException(String message) {
    super(ErrorCode.EVENT_CLOSED, message);
  }
}
