package com.sportsbook.betting.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Wire response from {@code POST /internal/v1/risk/check}. risk returns HTTP 200 for both verdicts;
 * {@code approved:false} is a business rejection (with a reason), not an error status. Only {@code
 * approved} + {@code rejectionReason} are consumed here — {@code limitInfo} / {@code
 * patternsFlagged} are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RiskCheckResponse(boolean approved, String rejectionReason) {}
