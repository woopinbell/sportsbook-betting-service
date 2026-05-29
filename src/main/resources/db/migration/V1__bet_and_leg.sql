-- V1: Bet slip aggregate (STI model) + bet_leg children (ADR-0008, ADR-0005, ADR-0003).
--
-- STI (Single Table Inheritance): one `bet` table holds Single / Multiple /
-- System slips, discriminated by slip_type. The K-of-N parameters
-- (system_min_wins / system_total_selections) are only populated for SYSTEM
-- slips and null otherwise — enforced by the bet_system_params check below.

CREATE TABLE bet (
    bet_id                  UUID                     PRIMARY KEY,
    user_id                 UUID                     NOT NULL,
    bet_reference           VARCHAR(32)              NOT NULL,
    slip_type               VARCHAR(16)              NOT NULL,
    system_min_wins         INTEGER,
    system_total_selections INTEGER,
    stake_amount            BIGINT                   NOT NULL,
    stake_currency          VARCHAR(3)               NOT NULL,
    max_payout_amount       BIGINT                   NOT NULL,
    max_payout_currency     VARCHAR(3)               NOT NULL,
    status                  VARCHAR(16)              NOT NULL,
    rejection_reason        VARCHAR(64),
    idempotency_key         VARCHAR(128)             NOT NULL,
    version                 BIGINT                   NOT NULL DEFAULT 0,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    -- ADR-0005: idempotency strong guarantee. The first placement with a given
    -- Idempotency-Key wins; a retry collides here and replays the same betId.
    CONSTRAINT uk_bet_idempotency_key UNIQUE (idempotency_key),
    -- Human-readable reference shown to the user (ADR-0003), globally unique.
    CONSTRAINT uk_bet_reference       UNIQUE (bet_reference),
    CONSTRAINT bet_slip_type_valid    CHECK (slip_type IN ('SINGLE', 'MULTIPLE', 'SYSTEM')),
    CONSTRAINT bet_status_valid       CHECK (status IN (
        'PENDING', 'ACCEPTED', 'REJECTED', 'SETTLED', 'CANCELLED', 'VOIDED'
    )),
    CONSTRAINT bet_stake_positive     CHECK (stake_amount    > 0),
    CONSTRAINT bet_max_payout_nonneg  CHECK (max_payout_amount >= 0),
    -- Stake and max payout are the same currency (no FX inside a slip in V1).
    CONSTRAINT bet_currency_match     CHECK (stake_currency = max_payout_currency),
    -- K-of-N parameters present iff the slip is a SYSTEM slip.
    CONSTRAINT bet_system_params CHECK (
        (slip_type =  'SYSTEM' AND system_min_wins IS NOT NULL AND system_total_selections IS NOT NULL)
     OR (slip_type <> 'SYSTEM' AND system_min_wins IS     NULL AND system_total_selections IS     NULL)
    )
);

COMMENT ON TABLE  bet                       IS 'Bet slip aggregate root. STI: slip_type discriminates Single / Multiple / System.';
COMMENT ON COLUMN bet.slip_type             IS 'SINGLE / MULTIPLE / SYSTEM (ADR-0008).';
COMMENT ON COLUMN bet.system_min_wins       IS 'K parameter for SYSTEM slips (1..total). Null otherwise.';
COMMENT ON COLUMN bet.system_total_selections IS 'N parameter for SYSTEM slips (= leg count). Null otherwise.';
COMMENT ON COLUMN bet.max_payout_amount     IS 'Worst-case payout computed at acceptance (audit). Real payout decided at settlement.';
COMMENT ON COLUMN bet.idempotency_key       IS 'Caller-supplied Idempotency-Key (ADR-0005). Also propagated as the wallet/risk key.';
COMMENT ON COLUMN bet.version               IS 'JPA @Version — optimistic lock for the PENDING -> ACCEPTED/REJECTED transition.';

CREATE TABLE bet_leg (
    leg_id             UUID         PRIMARY KEY,
    bet_id             UUID         NOT NULL REFERENCES bet (bet_id),
    leg_index          INTEGER      NOT NULL,
    event_id           UUID         NOT NULL,
    market_id          UUID         NOT NULL,
    selection_id       UUID         NOT NULL,
    odds_at_submission NUMERIC(9,4) NOT NULL,
    -- Preserves submission order and makes the leg set deterministic for the
    -- System K-of-N combination expansion. Also the index used to load a slip.
    CONSTRAINT uk_bet_leg_order  UNIQUE (bet_id, leg_index),
    CONSTRAINT bet_leg_odds_min  CHECK (odds_at_submission >= 1.0)
);

COMMENT ON TABLE  bet_leg                    IS 'One selection per row — child of the bet aggregate.';
COMMENT ON COLUMN bet_leg.odds_at_submission IS 'Decimal odds the user saw at submit (scale 4). Slippage-checked against live odds before acceptance.';

-- "A user's bets, newest first, keyset-paginated by bet_id" (ADR-0004 cursor
-- pagination). bet_id is UUID v7 (time-ordered) so it doubles as the cursor.
CREATE INDEX ix_bet_user_bet ON bet (user_id, bet_id DESC);

-- "PENDING bets older than T" — the reconciliation job's stale-slip scan
-- (ADR-0017 compensation).
CREATE INDEX ix_bet_status_created ON bet (status, created_at);
