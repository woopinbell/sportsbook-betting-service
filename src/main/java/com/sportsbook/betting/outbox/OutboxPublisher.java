package com.sportsbook.betting.outbox;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled drain of {@link OutboxEvent} rows out to Kafka. Runs at a fixed 1-second cadence; each
 * tick fetches up to {@link #BATCH_SIZE} unpublished rows, sends each to Kafka and waits for the
 * ack, then stamps {@code published_at}. Rows that fail to ack stay unpublished and are retried on
 * the next tick — that retry-until-acked behaviour is what makes the outbox at-least-once.
 *
 * <p>The publisher does not delete published rows; downstream replay is then a question of
 * resending rows with non-null {@code published_at}, which is cheaper to operate than a separate
 * archive table.
 */
@Component
public class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
  static final int BATCH_SIZE = 100;
  private static final long SEND_TIMEOUT_SECONDS = 5L;

  private final OutboxEventRepository repository;
  private final KafkaTemplate<String, byte[]> kafkaTemplate;
  private final Clock clock;

  public OutboxPublisher(
      OutboxEventRepository repository, KafkaTemplate<String, byte[]> kafkaTemplate, Clock clock) {
    this.repository = repository;
    this.kafkaTemplate = kafkaTemplate;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${betting.outbox.poll-interval-ms:1000}")
  @Transactional
  public void publishPending() {
    List<OutboxEvent> batch = repository.findUnpublished(PageRequest.of(0, BATCH_SIZE));
    if (batch.isEmpty()) {
      return;
    }
    for (OutboxEvent event : batch) {
      if (sendBlocking(event)) {
        event.markPublished(clock.instant());
      }
    }
  }

  private boolean sendBlocking(OutboxEvent event) {
    try {
      ProducerRecord<String, byte[]> record =
          new ProducerRecord<>(event.topic(), event.partitionKey(), event.payload());
      kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      return true;
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted publishing outbox event {}", event.eventId());
      return false;
    } catch (Exception e) {
      // Anything else: log and let the next tick retry. Permanent failures land in DLQ via
      // Spring Kafka's DefaultErrorHandler on the consumer side, but for the publisher itself
      // we keep retrying — duplicate sends are absorbed by the consumer's idempotency model.
      log.warn(
          "Failed to publish outbox event {} ({}): {}",
          event.eventId(),
          event.schemaName(),
          e.getMessage());
      return false;
    }
  }
}
