package com.sportsbook.betting.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Row in the transactional outbox (ADR-0006). Written in the same DB transaction as the matched
 * ledger pair so the event cannot diverge from the underlying ledger change. {@link
 * OutboxPublisher} polls for unpublished rows, sends them to Kafka, and stamps {@link
 * #publishedAt()} on success.
 *
 * <p>Almost all fields are write-once. The single mutable field is {@code publishedAt}; everything
 * else is set at construction by the static {@link #pending} factory.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

  @Id
  @Column(name = "event_id", nullable = false, updatable = false)
  private UUID eventId;

  @Column(name = "topic", nullable = false, length = 64, updatable = false)
  private String topic;

  @Column(name = "partition_key", nullable = false, length = 64, updatable = false)
  private String partitionKey;

  @Column(name = "schema_name", nullable = false, length = 64, updatable = false)
  private String schemaName;

  @Column(name = "payload", nullable = false, updatable = false)
  private byte[] payload;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "published_at")
  private Instant publishedAt;

  protected OutboxEvent() {
    // Required by JPA.
  }

  // Five fields, all distinct value-objects; a builder would obscure the call site.
  @SuppressWarnings("checkstyle:ParameterNumber")
  private OutboxEvent(
      UUID eventId,
      String topic,
      String partitionKey,
      String schemaName,
      byte[] payload,
      Instant createdAt) {
    this.eventId = Objects.requireNonNull(eventId, "eventId");
    this.topic = Objects.requireNonNull(topic, "topic");
    this.partitionKey = Objects.requireNonNull(partitionKey, "partitionKey");
    this.schemaName = Objects.requireNonNull(schemaName, "schemaName");
    this.payload = Objects.requireNonNull(payload, "payload").clone();
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  // Same justification as the private constructor.
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static OutboxEvent pending(
      UUID eventId,
      String topic,
      String partitionKey,
      String schemaName,
      byte[] payload,
      Instant createdAt) {
    return new OutboxEvent(eventId, topic, partitionKey, schemaName, payload, createdAt);
  }

  /** Marks the row as successfully published. Idempotent — second call is a no-op. */
  public void markPublished(Instant at) {
    if (publishedAt == null) {
      publishedAt = Objects.requireNonNull(at, "at");
    }
  }

  public UUID eventId() {
    return eventId;
  }

  public String topic() {
    return topic;
  }

  public String partitionKey() {
    return partitionKey;
  }

  public String schemaName() {
    return schemaName;
  }

  public byte[] payload() {
    return payload.clone();
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant publishedAt() {
    return publishedAt;
  }

  public boolean isPublished() {
    return publishedAt != null;
  }
}
