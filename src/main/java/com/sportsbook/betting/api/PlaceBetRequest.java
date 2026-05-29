package com.sportsbook.betting.api;

import com.sportsbook.protocol.value.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Body of {@code POST /internal/v1/bets} (ADR-0004 camelCase JSON). The {@code Idempotency-Key} is
 * a separate required header. Bean-validation guards presence/shape; richer domain rules (slip
 * structure, slippage, stake bounds) run in the placement flow.
 */
public record PlaceBetRequest(
    @NotNull UUID userId,
    @NotNull @Valid SlipTypeRequest slipType,
    @NotEmpty @Valid List<SelectionRequest> selections,
    @NotNull Money stake) {

  /**
   * {@code type} is SINGLE / MULTIPLE / SYSTEM; minWins + totalSelections are required for SYSTEM.
   */
  public record SlipTypeRequest(@NotBlank String type, Integer minWins, Integer totalSelections) {}

  /** One pick; {@code odds} is the decimal price the user saw at submit time. */
  public record SelectionRequest(
      @NotNull UUID eventId,
      @NotNull UUID marketId,
      @NotNull UUID selectionId,
      @NotNull BigDecimal odds) {}
}
