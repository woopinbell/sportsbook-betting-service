package com.sportsbook.betting.client;

import com.sportsbook.protocol.value.Money;
import java.util.List;

/**
 * Wire body for {@code POST /internal/v1/risk/check}. Betting owns its own copy of the contract —
 * risk-service's DTO lives in another repo and is not a shared dependency; the JSON shape is the
 * agreement. {@code Money} comes from shared-protocol, so it serializes identically on both sides.
 */
public record RiskCheckRequest(
    String userId, String betId, Money stake, List<String> selectionIds) {}
