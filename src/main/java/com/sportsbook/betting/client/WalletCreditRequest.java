package com.sportsbook.betting.client;

import com.sportsbook.protocol.value.Money;
import java.util.UUID;

/**
 * Wire body for {@code POST /internal/v1/wallet/transactions/credit} (wallet's CreditRequest). The
 * reconciliation rollback refunds the held stake, so {@code source} is {@code USER_LOCKED} — the
 * string value of wallet's {@code CreditCommand.Source} enum (BET_REFUND ledger reason).
 */
public record WalletCreditRequest(UUID userId, Money amount, String source) {}
