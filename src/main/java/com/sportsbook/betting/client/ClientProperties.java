package com.sportsbook.betting.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Endpoints + timeouts for the synchronous risk / wallet dependencies (ADR-0017), bound from {@code
 * betting.clients.*}. The timeouts are the fail-fast bound on the placement critical path — a slow
 * dependency surfaces as a {@code ResourceAccessException} rather than stalling the request thread.
 */
@ConfigurationProperties(prefix = "betting.clients")
public record ClientProperties(
    String riskBaseUrl, String walletBaseUrl, Duration connectTimeout, Duration readTimeout) {

  private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofMillis(200);
  private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofMillis(500);

  public ClientProperties {
    if (connectTimeout == null) {
      connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    }
    if (readTimeout == null) {
      readTimeout = DEFAULT_READ_TIMEOUT;
    }
  }
}
