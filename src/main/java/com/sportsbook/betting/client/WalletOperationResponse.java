package com.sportsbook.betting.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Wire response from a successful wallet transaction. Only {@code operationGroupId} is consumed
 * (for tracing the debit/credit across services); the rest of wallet's payload is ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WalletOperationResponse(UUID operationGroupId) {}
