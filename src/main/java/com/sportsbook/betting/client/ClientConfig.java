package com.sportsbook.betting.client;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the two synchronous-dependency {@link RestClient}s (ADR-0017). Each gets the configured
 * base URL and the same fail-fast connect/read timeouts; the autoconfigured builder is cloned so
 * the Boot customizations (Micrometer observation, etc.) carry over without the two beans sharing
 * mutable state.
 */
@Configuration
public class ClientConfig {

  @Bean
  RestClient riskRestClient(RestClient.Builder builder, ClientProperties properties) {
    return builder
        .clone()
        .baseUrl(properties.riskBaseUrl())
        .requestFactory(requestFactory(properties))
        .build();
  }

  @Bean
  RestClient walletRestClient(RestClient.Builder builder, ClientProperties properties) {
    return builder
        .clone()
        .baseUrl(properties.walletBaseUrl())
        .requestFactory(requestFactory(properties))
        .build();
  }

  private static ClientHttpRequestFactory requestFactory(ClientProperties properties) {
    ClientHttpRequestFactorySettings settings =
        ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(properties.connectTimeout())
            .withReadTimeout(properties.readTimeout());
    return ClientHttpRequestFactories.get(settings);
  }
}
