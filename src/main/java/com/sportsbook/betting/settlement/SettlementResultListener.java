package com.sportsbook.betting.settlement;

import com.sportsbook.betting.outbox.AvroSerializer;
import com.sportsbook.protocol.domain.SettlementResult;
import com.sportsbook.protocol.event.BetSettled;
import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.Money;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka boundary for the async settlement events (ADR-0006). settlement-service publishes {@code
 * BetSettled} / {@code BetVoided} as Schema-Registry-less Avro bytes (ADR-0014, same wire shape as
 * the outbox producer); this listener decodes with the pinned shared-protocol classes and hands
 * plain domain values to {@link BetSettlementService}. Keeping the Avro types at this edge stops
 * the wire schema leaking into the aggregate.
 *
 * <p>{@code autoStartup} is a property so integration tests that do not stand up a broker can leave
 * the container parked (mirrors the parked {@code @Scheduled} jobs); it defaults to on in
 * production.
 */
@Component
public class SettlementResultListener {

  static final String SETTLED_TOPIC = "bet.settled.v1";
  private static final String AUTO_STARTUP = "${betting.kafka.consumer.auto-startup:true}";

  private static final Logger log = LoggerFactory.getLogger(SettlementResultListener.class);

  private final BetSettlementService service;

  public SettlementResultListener(BetSettlementService service) {
    this.service = service;
  }

  @KafkaListener(topics = SETTLED_TOPIC, autoStartup = AUTO_STARTUP)
  public void onBetSettled(byte[] payload) {
    BetSettled event = AvroSerializer.deserialize(payload, BetSettled.class);
    UUID betId = UUID.fromString(event.getBetId().toString());
    SettlementResult result = SettlementResult.valueOf(event.getResult().name());
    log.debug("BetSettled received: betId={} result={}", betId, result);
    service.applySettled(betId, result, toMoney(event.getPayout()), event.getSettledAt());
  }

  private static Money toMoney(com.sportsbook.protocol.event.Money money) {
    return new Money(money.getAmount(), Currency.valueOf(money.getCurrency().toString()));
  }
}
