-- V3: Bet settlement outcome columns (ADR-0006 async settlement, ADR-0013).
--
-- betting-service consumes BetSettled (settlement-service publishes it after a
-- MatchResult) and flips an ACCEPTED slip to SETTLED, recording the adjudicated
-- result and the actual payout credited to the wallet. max_payout (V1) is the
-- worst-case figure stored at acceptance for audit; this records the real
-- settle-time payout. resolved_at is the terminal-transition timestamp, shared
-- with the VOIDED path added in V4.

ALTER TABLE bet
    ADD COLUMN settlement_result       VARCHAR(8),
    ADD COLUMN settled_payout_amount   BIGINT,
    ADD COLUMN settled_payout_currency VARCHAR(3),
    ADD COLUMN resolved_at             TIMESTAMP WITH TIME ZONE;

-- ADR-0013 SettlementResult. WON/PUSH pay out, LOST pays zero, VOID is a
-- per-selection settle-time refund (distinct from a whole-slip VOIDED, V4).
ALTER TABLE bet ADD CONSTRAINT bet_settlement_result_valid
    CHECK (settlement_result IS NULL OR settlement_result IN ('WON', 'LOST', 'PUSH', 'VOID'));

-- Payout amount and currency are written together (both null pre-settlement,
-- both set on SETTLED); the payout never goes negative.
ALTER TABLE bet ADD CONSTRAINT bet_settled_payout_paired
    CHECK ((settled_payout_amount IS NULL) = (settled_payout_currency IS NULL));
ALTER TABLE bet ADD CONSTRAINT bet_settled_payout_nonneg
    CHECK (settled_payout_amount IS NULL OR settled_payout_amount >= 0);
-- No FX inside a slip (V1): the payout currency matches the stake currency.
ALTER TABLE bet ADD CONSTRAINT bet_settled_payout_currency_match
    CHECK (settled_payout_currency IS NULL OR settled_payout_currency = stake_currency);

COMMENT ON COLUMN bet.settlement_result       IS 'ADR-0013 SettlementResult on SETTLED (WON/LOST/PUSH/VOID). Null until settled.';
COMMENT ON COLUMN bet.settled_payout_amount   IS 'Actual payout credited at settlement (minor units). Null until settled.';
COMMENT ON COLUMN bet.settled_payout_currency IS 'Currency of the settle-time payout; equals stake_currency. Null until settled.';
COMMENT ON COLUMN bet.resolved_at             IS 'Terminal-transition time: settledAt (SETTLED) or voidedAt (VOIDED).';
