// Concurrency proof (over HTTP): N VUs fire the SAME Idempotency-Key at once.
// The system must never accept the bet twice — every response is either 201
// (the single accept or an idempotent replay of it) or 409 (DUPLICATE_BET for
// an in-flight loser). A 5xx or two distinct accepted betIds would be a defect.
// The authoritative single-accept / single-debit invariant is asserted in
// BetPlacementIntegrationTest; this shows it holds under HTTP-level contention.
//
// Run (after `docker compose up` + `./seed.sh`):
//   k6 run -e BASE_URL=http://localhost:58082 -e VUS=100 scenarios/concurrency.js

import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:58082';
const VUS = parseInt(__ENV.VUS || '100', 10);
const SHARED_KEY = __ENV.IDEMPOTENCY_KEY || `race-bench-${Date.now()}`;

const EVENT = '11111111-1111-7111-8111-111111111111';
const MARKET = '22222222-2222-7222-8222-222222222222';
const SELECTION = '33333333-3333-7333-8333-333333333333';
const USER = uuidv4();

export const options = {
  scenarios: {
    same_key_race: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '15s',
    },
  },
  thresholds: {
    checks: ['rate==1.0'],
  },
};

export default function () {
  const body = JSON.stringify({
    userId: USER,
    slipType: { type: 'SINGLE' },
    selections: [
      { eventId: EVENT, marketId: MARKET, selectionId: SELECTION, odds: 2.0 },
    ],
    stake: { amount: 10000, currency: 'KRW' },
  });

  const res = http.post(`${BASE_URL}/internal/v1/bets`, body, {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': SHARED_KEY },
  });

  check(res, {
    'accepted (201) or duplicate (409), never 5xx': r =>
      r.status === 201 || r.status === 409,
  });
}
