package com.sportsbook.betting.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Producer factory for the outbox publisher. The wire shape is {@code (String key, byte[] value)}
 * because the outbox row carries the Avro-encoded payload as raw bytes — V1 publishes without a
 * Schema Registry (ADR-0014), each consumer pins the same shared-protocol Avro classes.
 *
 * <p>Idempotent producer is on so retries inside the broker do not duplicate records; combined with
 * the outbox's "retry until acked" loop, the end-to-end delivery is at-least-once with
 * partition-level ordering preserved.
 */
@Configuration
public class KafkaConfig {

  // Confluent-recommended ceiling with enable.idempotence=true.
  private static final int MAX_IN_FLIGHT_REQUESTS = 5;

  // Settlement consumer error handling: retry transient failures, then dead-letter.
  private static final long RETRY_INTERVAL_MS = 1000L;
  private static final long MAX_RETRIES = 3L;

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  @Bean
  public ProducerFactory<String, byte[]> bettingProducerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, MAX_IN_FLIGHT_REQUESTS);
    props.put(ProducerConfig.CLIENT_ID_CONFIG, "betting-service-outbox");
    return new DefaultKafkaProducerFactory<>(props);
  }

  @Bean
  public KafkaTemplate<String, byte[]> bettingKafkaTemplate(
      ProducerFactory<String, byte[]> bettingProducerFactory) {
    return new KafkaTemplate<>(bettingProducerFactory);
  }

  // Consumer side (ADR-0006 async settlement). settlement-service publishes
  // BetSettled / BetVoided as Avro bytes under the same Schema-Registry-less wire
  // shape as the producer (ADR-0014); the listener decodes with the pinned
  // shared-protocol classes. A single consumer group shares partitions across
  // service instances.
  @Bean
  public ConsumerFactory<String, byte[]> bettingConsumerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "betting-service-settlement");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    return new DefaultKafkaConsumerFactory<>(props);
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, byte[]> kafkaListenerContainerFactory(
      ConsumerFactory<String, byte[]> bettingConsumerFactory, DefaultErrorHandler errorHandler) {
    ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(bettingConsumerFactory);
    factory.setCommonErrorHandler(errorHandler);
    // Commit per record: a settlement update is idempotent (re-applied as a no-op
    // once terminal), so committing each offset keeps redelivery to one record.
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
    return factory;
  }

  @Bean
  public DefaultErrorHandler settlementErrorHandler(
      KafkaTemplate<String, byte[]> bettingKafkaTemplate) {
    // Transient failures (a DB blip) get a few backed-off retries; anything still
    // failing is a poison record (undecodable, unknown bet, illegal transition) and
    // is published to <topic>.DLT instead of blocking the partition — the DLQ path
    // the outbox publisher's comment refers to. DLT topics are provisioned in the
    // broker (orchestration), not auto-created here.
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(bettingKafkaTemplate);
    return new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
  }
}
