package com.sportsbook.betting.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.sportsbook.betting.error.DependencyUnavailableException;
import com.sportsbook.betting.error.InsufficientBalanceException;
import com.sportsbook.betting.infrastructure.id.UuidV7;
import com.sportsbook.protocol.value.Money;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.web.client.RestClient;

/**
 * Response-translation coverage for {@link WalletClient} against a WireMock wallet-service stub.
 */
class WalletClientTest {

  private static final String DEBIT_PATH = "/internal/v1/wallet/transactions/debit";
  private static final String CREDIT_PATH = "/internal/v1/wallet/transactions/credit";
  private static final UUID BET = UuidV7.generate();
  private static final UUID USER = UuidV7.generate();
  private static final Money AMOUNT = Money.krw(10_000);

  private static WireMockServer wm;

  @BeforeAll
  static void startServer() {
    wm = new WireMockServer(options().dynamicPort());
    wm.start();
  }

  @AfterAll
  static void stopServer() {
    wm.stop();
  }

  @BeforeEach
  void reset() {
    wm.resetAll();
  }

  private WalletClient client(Duration readTimeout) {
    ClientHttpRequestFactorySettings settings =
        ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(Duration.ofMillis(300))
            .withReadTimeout(readTimeout);
    RestClient http =
        RestClient.builder()
            .baseUrl(wm.baseUrl())
            .requestFactory(ClientHttpRequestFactories.get(settings))
            .build();
    return new WalletClient(http, new ObjectMapper());
  }

  @Test
  @DisplayName("200 -> returns operation id and sends betId as the Idempotency-Key")
  void debitSuccess() {
    UUID operationGroupId = UuidV7.generate();
    wm.stubFor(
        post(urlEqualTo(DEBIT_PATH))
            .willReturn(okJson("{\"operationGroupId\":\"" + operationGroupId + "\"}")));

    UUID result = client(Duration.ofMillis(500)).debit(BET, USER, AMOUNT);

    assertThat(result).isEqualTo(operationGroupId);
    wm.verify(
        postRequestedFor(urlEqualTo(DEBIT_PATH))
            .withHeader("Idempotency-Key", equalTo(BET.toString())));
  }

  @Test
  @DisplayName("422 WALLET_INSUFFICIENT_BALANCE -> InsufficientBalanceException")
  void insufficientBalance() {
    wm.stubFor(
        post(urlEqualTo(DEBIT_PATH))
            .willReturn(
                aResponse()
                    .withStatus(422)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody(
                        "{\"code\":\"WALLET_INSUFFICIENT_BALANCE\",\"detail\":\"balance 5000 < 10000\"}")));

    assertThatThrownBy(() -> client(Duration.ofMillis(500)).debit(BET, USER, AMOUNT))
        .isInstanceOf(InsufficientBalanceException.class)
        .hasMessageContaining("5000");
  }

  @Test
  @DisplayName("5xx -> DependencyUnavailableException (fail-closed)")
  void serverError() {
    wm.stubFor(post(urlEqualTo(DEBIT_PATH)).willReturn(aResponse().withStatus(503)));

    assertThatThrownBy(() -> client(Duration.ofMillis(500)).debit(BET, USER, AMOUNT))
        .isInstanceOf(DependencyUnavailableException.class);
  }

  @Test
  @DisplayName("read timeout -> DependencyUnavailableException (fail-closed)")
  void timeout() {
    wm.stubFor(
        post(urlEqualTo(DEBIT_PATH))
            .willReturn(
                okJson("{\"operationGroupId\":\"" + UuidV7.generate() + "\"}")
                    .withFixedDelay(1_000)));

    assertThatThrownBy(() -> client(Duration.ofMillis(150)).debit(BET, USER, AMOUNT))
        .isInstanceOf(DependencyUnavailableException.class);
  }

  @Test
  @DisplayName("refund credits with the refund:<betId> idempotency key")
  void refundSuccess() {
    UUID operationGroupId = UuidV7.generate();
    wm.stubFor(
        post(urlEqualTo(CREDIT_PATH))
            .willReturn(okJson("{\"operationGroupId\":\"" + operationGroupId + "\"}")));

    UUID result = client(Duration.ofMillis(500)).refund(BET, USER, AMOUNT);

    assertThat(result).isEqualTo(operationGroupId);
    wm.verify(
        postRequestedFor(urlEqualTo(CREDIT_PATH))
            .withHeader("Idempotency-Key", equalTo("refund:" + BET)));
  }
}
