package com.sportsbook.betting.error;

import com.sportsbook.protocol.error.ErrorCode;

/**
 * Slip failed a structural policy rule (ADR-0008 L1/L2/L4/L5) or a stake bound (ADR-0009). Maps to
 * {@link ErrorCode#VALIDATION_FAILED} (HTTP 400). The message names the specific rule for the
 * caller and the logs.
 */
public class ValidationFailedException extends BetPlacementException {

  public ValidationFailedException(String message) {
    super(ErrorCode.VALIDATION_FAILED, message);
  }
}
