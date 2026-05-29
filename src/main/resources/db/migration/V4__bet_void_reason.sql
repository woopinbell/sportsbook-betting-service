-- V4: Whole-slip void reason (ADR-0012: cancelled / postponed events void the bet).
--
-- betting-service consumes BetVoided (settlement-service publishes it when an
-- event is cancelled/postponed, or a market / admin void applies) and flips an
-- ACCEPTED slip to VOIDED; wallet-service handles the full stake refund. The
-- void reuses resolved_at (V3) as its terminal-transition timestamp.

ALTER TABLE bet ADD COLUMN void_reason VARCHAR(24);

ALTER TABLE bet ADD CONSTRAINT bet_void_reason_valid
    CHECK (void_reason IS NULL OR void_reason IN (
        'EVENT_CANCELLED', 'EVENT_POSTPONED', 'MARKET_VOID', 'ADMIN_VOID'
    ));

COMMENT ON COLUMN bet.void_reason IS 'Why a VOIDED slip was voided (ADR-0012). Null unless status = VOIDED.';
