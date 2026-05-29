package com.sportsbook.betting.error;

import com.sportsbook.protocol.error.ErrorCode;

/**
 * Base type for every business rejection on the placement path. Each carries a shared-protocol
 * {@link ErrorCode} so the {@code @ControllerAdvice} can render a single RFC 7807 ProblemDetail
 * shape (ADR-0004) without a per-exception mapping table.
 *
 * <p>These are business rejections, not infrastructure failures: they are the expected "no" answers
 * (slip invalid, odds drifted, market closed, limit hit, balance short) and must never be counted
 * as Resilience4j circuit-breaker failures on the risk/wallet calls (ADR-0017).
 */
public abstract class BetPlacementException extends RuntimeException {

  private final transient ErrorCode errorCode;

  protected BetPlacementException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public ErrorCode errorCode() {
    return errorCode;
  }
}
