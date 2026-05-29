package com.sportsbook.betting.error;

import com.sportsbook.protocol.error.ErrorCode;

/**
 * A synchronous dependency (risk or wallet) failed in a non-business way — timeout, connection
 * error, 5xx, an unexpected response, or an open circuit breaker (ADR-0017). This is the
 * fail-closed signal: the bet is rejected because we could not safely confirm the limit check or
 * the debit.
 *
 * <p>Mapped to {@link ErrorCode#INTERNAL_ERROR} (HTTP 500) in V1 — the shared catalog has no
 * dedicated SERVICE_UNAVAILABLE code yet, and the failure is retryable from the caller's side. This
 * is the only exception the client circuit breakers record, so a flood of these (not business
 * rejections) is what opens a breaker.
 */
public class DependencyUnavailableException extends BetPlacementException {

  public DependencyUnavailableException(String message) {
    super(ErrorCode.INTERNAL_ERROR, message);
  }

  public DependencyUnavailableException(String message, Throwable cause) {
    super(ErrorCode.INTERNAL_ERROR, message);
    initCause(cause);
  }
}
