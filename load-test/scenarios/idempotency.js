// Level-3 proof (over HTTP): N requests with the SAME Idempotency-Key must
// collapse to exactly one accepted bet — every response carries the same betId.
// Sequential single-VU loop so the first request commits before the replays,
// giving the clean "same betId every time" demonstration. (The concurrent race
// + single-debit invariant is also covered by BetPlacementIntegrationTest.)
//
// Run (after `docker compose up` + `./seed.sh`):
//   k6 run -e BASE_URL=http://localhost:58082 scenarios/idempotency.js

import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:58082';
const REPEATS = parseInt(__ENV.REPEATS || '50', 10);
const SHARED_KEY = __ENV.IDEMPOTENCY_KEY || `idem-bench-${Date.now()}`;

const EVENT = '11111111-1111-7111-8111-111111111111';
const MARKET = '22222222-2222-7222-8222-222222222222';
const SELECTION = '33333333-3333-7333-8333-333333333333';
const USER = uuidv4();

export const options = {
  scenarios: {
    same_key_replay: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: REPEATS,
      maxDuration: '30s',
    },
  },
  thresholds: {
    checks: ['rate==1.0'],
  },
};

let firstBetId = null;

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

  const betId = res.status === 201 && res.json() ? res.json().betId : null;
  if (firstBetId === null) {
    firstBetId = betId;
  }
  check(res, {
    'status is 201': r => r.status === 201,
    'same betId as the first accept': () => betId !== null && betId === firstBetId,
  });
}
