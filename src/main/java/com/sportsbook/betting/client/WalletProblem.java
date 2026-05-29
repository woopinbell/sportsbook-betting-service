package com.sportsbook.betting.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Subset of wallet's RFC 7807 error body. wallet uses Spring's {@code ProblemDetail} with a custom
 * {@code code} property (e.g. {@code WALLET_INSUFFICIENT_BALANCE}); betting reads {@code code} to
 * tell the one business rejection (insufficient balance) apart from unexpected client errors.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WalletProblem(String code, String detail) {}
