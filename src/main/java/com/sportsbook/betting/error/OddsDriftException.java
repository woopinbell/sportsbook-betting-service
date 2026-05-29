package com.sportsbook.betting.error;

import com.sportsbook.protocol.error.ErrorCode;

/**
 * The live odds moved against the user beyond the slippage tolerance (ADR-0008, default 3 %). Maps
 * to {@link ErrorCode#ODDS_DRIFT} (HTTP 409) — the client can resubmit at the new price.
 */
public class OddsDriftException extends BetPlacementException {

  public OddsDriftException(String message) {
    super(ErrorCode.ODDS_DRIFT, message);
  }
}
