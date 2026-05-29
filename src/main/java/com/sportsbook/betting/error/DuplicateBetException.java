package com.sportsbook.betting.error;

import com.sportsbook.protocol.error.ErrorCode;

/**
 * A bet with the same {@code Idempotency-Key} is already being processed and has not yet committed.
 * Maps to {@link ErrorCode#DUPLICATE_BET} (HTTP 409). Once the in-flight request commits, a retry
 * with the same key returns that bet instead (idempotent replay, ADR-0005).
 */
public class DuplicateBetException extends BetPlacementException {

  public DuplicateBetException(String message) {
    super(ErrorCode.DUPLICATE_BET, message);
  }
}
