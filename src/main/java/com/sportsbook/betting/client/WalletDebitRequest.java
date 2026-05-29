package com.sportsbook.betting.client;

import com.sportsbook.protocol.value.Money;
import java.util.UUID;

/**
 * Wire body for {@code POST /internal/v1/wallet/transactions/debit} (wallet's TransactionRequest).
 */
public record WalletDebitRequest(UUID userId, Money amount) {}
