package com.sportsbook.betting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Betting service entry point.
 *
 * <p>Owns bet placement: slip structural validation (ADR-0008), odds-slippage checking against the
 * odds-feed Redis cache, and the synchronous risk-then-wallet orchestration that accepts or rejects
 * a slip (ADR-0017). Scheduling is enabled application-wide so the outbox publisher and the
 * reconciliation job can declare {@code @Scheduled} hooks without per-feature plumbing.
 *
 * <p>{@code @ConfigurationPropertiesScan} binds {@code betting.policy.*} into {@code
 * BettingPolicyProperties} (ADR-0009) without an explicit {@code @EnableConfigurationProperties}.
 */
// @SpringBootApplication is meta-annotated with @Configuration, so Spring instantiates this class
// as a bean; a private constructor would break that. Suppress the utility-class rule explicitly.
@SuppressWarnings("checkstyle:HideUtilityClassConstructor")
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class BettingServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(BettingServiceApplication.class, args);
  }
}
