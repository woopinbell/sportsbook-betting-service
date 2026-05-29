package com.sportsbook.betting.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.sportsbook.betting.error.DependencyUnavailableException;
import com.sportsbook.betting.error.RiskLimitException;
import com.sportsbook.betting.infrastructure.id.UuidV7;
import com.sportsbook.protocol.value.Money;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.web.client.RestClient;

/** Response-translation coverage for {@link RiskClient} against a WireMock risk-service stub. */
class RiskClientTest {

  private static final String CHECK_PATH = "/internal/v1/risk/check";
  private static final UUID BET = UuidV7.generate();
  private static final UUID USER = UuidV7.generate();
  private static final Money STAKE = Money.krw(10_000);
  private static final List<String> SELECTIONS = List.of(UuidV7.generate().toString());

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

  private RiskClient client(Duration readTimeout) {
    ClientHttpRequestFactorySettings settings =
        ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(Duration.ofMillis(300))
            .withReadTimeout(readTimeout);
    RestClient http =
        RestClient.builder()
            .baseUrl(wm.baseUrl())
            .requestFactory(ClientHttpRequestFactories.get(settings))
            .build();
    return new RiskClient(http);
  }

  @Test
  @DisplayName("approved -> returns normally")
  void approved() {
    wm.stubFor(
        post(urlEqualTo(CHECK_PATH))
            .willReturn(okJson("{\"approved\":true,\"patternsFlagged\":[]}")));

    assertThatCode(() -> client(Duration.ofMillis(500)).check(BET, USER, STAKE, SELECTIONS))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("approved:false (HTTP 200) -> RiskLimitException, not a breaker failure")
  void declined() {
    wm.stubFor(
        post(urlEqualTo(CHECK_PATH))
            .willReturn(okJson("{\"approved\":false,\"rejectionReason\":\"DAILY_STAKE_LIMIT\"}")));

    assertThatThrownBy(() -> client(Duration.ofMillis(500)).check(BET, USER, STAKE, SELECTIONS))
        .isInstanceOf(RiskLimitException.class)
        .hasMessageContaining("DAILY_STAKE_LIMIT");
  }

  @Test
  @DisplayName("5xx -> DependencyUnavailableException (fail-closed)")
  void serverError() {
    wm.stubFor(post(urlEqualTo(CHECK_PATH)).willReturn(aResponse().withStatus(500)));

    assertThatThrownBy(() -> client(Duration.ofMillis(500)).check(BET, USER, STAKE, SELECTIONS))
        .isInstanceOf(DependencyUnavailableException.class);
  }

  @Test
  @DisplayName("read timeout -> DependencyUnavailableException (fail-closed)")
  void timeout() {
    wm.stubFor(
        post(urlEqualTo(CHECK_PATH))
            .willReturn(okJson("{\"approved\":true}").withFixedDelay(1_000)));

    assertThatThrownBy(() -> client(Duration.ofMillis(150)).check(BET, USER, STAKE, SELECTIONS))
        .isInstanceOf(DependencyUnavailableException.class);
  }
}
