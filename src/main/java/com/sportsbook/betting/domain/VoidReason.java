package com.sportsbook.betting.domain;

/**
 * Why a whole slip was voided and the stake fully refunded (ADR-0012). Mirrors the four symbols of
 * the shared-protocol {@code BetVoided} event enum, kept as a betting-local domain type so the
 * Avro-generated event class never leaks into the JPA aggregate. Distinct from {@code
 * SettlementResult#VOID}, which is a per-selection settle-time refund on an otherwise SETTLED slip.
 */
public enum VoidReason {
  EVENT_CANCELLED,
  EVENT_POSTPONED,
  MARKET_VOID,
  ADMIN_VOID,
}
