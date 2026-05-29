package com.sportsbook.betting.error;

/**
 * No bet exists for the requested id. Not a {@link BetPlacementException} (it is a lookup miss, not
 * a placement verdict); the controller advice maps it to HTTP 404 with a {@code BET_NOT_FOUND}
 * code. The shared ErrorCode catalog deliberately omits generic NOT_FOUND, so this stays a
 * betting-local concern.
 */
public class BetNotFoundException extends RuntimeException {

  public BetNotFoundException(String message) {
    super(message);
  }
}
