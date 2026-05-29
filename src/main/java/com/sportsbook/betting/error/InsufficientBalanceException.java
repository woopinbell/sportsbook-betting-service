package com.sportsbook.betting.error;

import com.sportsbook.protocol.error.ErrorCode;

/**
 * wallet-service declined the debit because the user's available balance is too low. wallet reports
 * this as HTTP 422 with a {@code code} of {@code WALLET_INSUFFICIENT_BALANCE}; betting re-expresses
 * it through the shared catalog as {@link ErrorCode#INSUFFICIENT_BALANCE} (HTTP 409). A business
 * rejection, not an infrastructure failure.
 */
public class InsufficientBalanceException extends BetPlacementException {

  public InsufficientBalanceException(String message) {
    super(ErrorCode.INSUFFICIENT_BALANCE, message == null ? "Insufficient balance" : message);
  }
}
