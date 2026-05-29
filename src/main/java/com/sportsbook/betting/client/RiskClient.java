package com.sportsbook.betting.client;

import com.sportsbook.betting.error.BetPlacementException;
import com.sportsbook.betting.error.DependencyUnavailableException;
import com.sportsbook.betting.error.RiskLimitException;
import com.sportsbook.protocol.value.Money;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Synchronous risk-limit check on the placement critical path (ADR-0017). risk-service answers HTTP
 * 200 for both verdicts, so a declined bet is a {@link RiskLimitException} (business — never trips
 * the breaker), while any transport failure (timeout, connection, 5xx) is translated to {@link
 * DependencyUnavailableException} (fail-closed — the only exception this breaker records).
 *
 * <p>The translation happens in the method body, so it holds with or without the AOP proxy; the
 * {@link CircuitBreaker} adds breaking + the open-circuit fallback on top.
 */
@Component
public class RiskClient {

  private static final String CHECK_PATH = "/internal/v1/risk/check";

  private final RestClient http;

  public RiskClient(@Qualifier("riskRestClient") RestClient http) {
    this.http = http;
  }

  /**
   * Returns normally if the bet is within limits. Throws {@link RiskLimitException} when risk
   * declines it, or {@link DependencyUnavailableException} when risk is unreachable.
   */
  @CircuitBreaker(name = "riskClient", fallbackMethod = "checkFallback")
  public void check(UUID betId, UUID userId, Money stake, List<String> selectionIds) {
    RiskCheckResponse response;
    try {
      response =
          http.post()
              .uri(CHECK_PATH)
              .contentType(MediaType.APPLICATION_JSON)
              .body(new RiskCheckRequest(userId.toString(), betId.toString(), stake, selectionIds))
              .retrieve()
              .body(RiskCheckResponse.class);
    } catch (RestClientException e) {
      throw new DependencyUnavailableException("risk-service check failed: " + e.getMessage(), e);
    }
    if (response == null || !response.approved()) {
      throw new RiskLimitException(response == null ? null : response.rejectionReason());
    }
  }

  // Invoked by Resilience4j when the circuit is open (CallNotPermittedException) or a recorded
  // failure escapes. Business rejections are re-thrown unchanged; everything else fails closed.
  private void checkFallback(
      UUID betId, UUID userId, Money stake, List<String> selectionIds, Throwable t) {
    if (t instanceof BetPlacementException placement) {
      throw placement;
    }
    throw new DependencyUnavailableException("risk-service unavailable (circuit open)", t);
  }
}
