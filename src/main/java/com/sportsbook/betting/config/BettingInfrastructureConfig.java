package com.sportsbook.betting.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared infrastructure beans. */
@Configuration
public class BettingInfrastructureConfig {

  /**
   * A UTC {@link Clock} so every {@code Instant} the service stamps is timezone-safe (ADR-0003) and
   * tests can swap in a fixed clock.
   */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
