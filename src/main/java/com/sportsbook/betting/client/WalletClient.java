package com.sportsbook.betting.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportsbook.betting.error.BetPlacementException;
import com.sportsbook.betting.error.DependencyUnavailableException;
import com.sportsbook.betting.error.InsufficientBalanceException;
import com.sportsbook.protocol.value.Money;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.io.IOException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Synchronous wallet calls on the placement critical path (ADR-0017). {@code betId} is the
 * Idempotency-Key for the debit so a retry is safe; the rollback refund uses {@code refund:<betId>}
 * so it dedups independently.
 *
 * <p>wallet returns HTTP 422 with {@code code = WALLET_INSUFFICIENT_BALANCE} for the one business
 * rejection — translated to {@link InsufficientBalanceException} (never trips the breaker). Any
 * transport failure (timeout, connection, 5xx) or unexpected client error becomes {@link
 * DependencyUnavailableException} (fail-closed — the only recorded exception).
 */
@Component
public class WalletClient {

  private static final String DEBIT_PATH = "/internal/v1/wallet/transactions/debit";
  private static final String CREDIT_PATH = "/internal/v1/wallet/transactions/credit";
  private static final String INSUFFICIENT_BALANCE_CODE = "WALLET_INSUFFICIENT_BALANCE";
  // wallet's CreditCommand.Source value that refunds the held stake from the locked bucket.
  private static final String REFUND_SOURCE = "USER_LOCKED";

  private final RestClient http;
  private final ObjectMapper objectMapper;

  public WalletClient(@Qualifier("walletRestClient") RestClient http, ObjectMapper objectMapper) {
    this.http = http;
    this.objectMapper = objectMapper;
  }

  /** Debits the stake. Returns the wallet operation id; throws on insufficient balance / outage. */
  @CircuitBreaker(name = "walletClient", fallbackMethod = "debitFallback")
  public UUID debit(UUID betId, UUID userId, Money amount) {
    try {
      WalletOperationResponse response =
          http.post()
              .uri(DEBIT_PATH)
              .header("Idempotency-Key", betId.toString())
              .contentType(MediaType.APPLICATION_JSON)
              .body(new WalletDebitRequest(userId, amount))
              .retrieve()
              .onStatus(HttpStatusCode::is4xxClientError, (request, res) -> throwMapped(res))
              .body(WalletOperationResponse.class);
      return response == null ? null : response.operationGroupId();
    } catch (BetPlacementException e) {
      throw e;
    } catch (RestClientException e) {
      throw new DependencyUnavailableException("wallet debit failed: " + e.getMessage(), e);
    }
  }

  /**
   * Refunds the held stake back to the user (reconciliation rollback, ADR-0017). Credits from the
   * locked bucket ({@code USER_LOCKED}) under a distinct {@code refund:<betId>} idempotency key.
   */
  @CircuitBreaker(name = "walletClient", fallbackMethod = "refundFallback")
  public UUID refund(UUID betId, UUID userId, Money amount) {
    try {
      WalletOperationResponse response =
          http.post()
              .uri(CREDIT_PATH)
              .header("Idempotency-Key", "refund:" + betId)
              .contentType(MediaType.APPLICATION_JSON)
              .body(new WalletCreditRequest(userId, amount, REFUND_SOURCE))
              .retrieve()
              .onStatus(HttpStatusCode::is4xxClientError, (request, res) -> throwMapped(res))
              .body(WalletOperationResponse.class);
      return response == null ? null : response.operationGroupId();
    } catch (BetPlacementException e) {
      throw e;
    } catch (RestClientException e) {
      throw new DependencyUnavailableException("wallet refund failed: " + e.getMessage(), e);
    }
  }

  private void throwMapped(ClientHttpResponse response) {
    throw mapClientError(response);
  }

  private RuntimeException mapClientError(ClientHttpResponse response) {
    try {
      WalletProblem problem = objectMapper.readValue(response.getBody(), WalletProblem.class);
      if (INSUFFICIENT_BALANCE_CODE.equals(problem.code())) {
        return new InsufficientBalanceException(problem.detail());
      }
      return new DependencyUnavailableException(
          "unexpected wallet client error: code=" + problem.code());
    } catch (IOException io) {
      return new DependencyUnavailableException("unreadable wallet error response", io);
    }
  }

  private UUID debitFallback(UUID betId, UUID userId, Money amount, Throwable t) {
    return failClosed(t);
  }

  private UUID refundFallback(UUID betId, UUID userId, Money amount, Throwable t) {
    return failClosed(t);
  }

  private static UUID failClosed(Throwable t) {
    if (t instanceof BetPlacementException placement) {
      throw placement;
    }
    throw new DependencyUnavailableException("wallet-service unavailable (circuit open)", t);
  }
}
