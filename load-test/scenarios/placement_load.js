// Sustained-load benchmark for the synchronous placement path (ADR-0017):
// validate -> slippage (Redis) -> risk (stub) -> wallet (stub) -> accept ->
// outbox. Each iteration is a fresh single-selection slip on the pre-seeded
// selection, with a unique Idempotency-Key, by a random user.
//
// Run (after `docker compose up` + `./seed.sh`):
//   k6 run -e BASE_URL=http://localhost:58082 -e RATE=500 -e DURATION=60s \
//          scenarios/placement_load.js

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:58082';
const RATE = parseInt(__ENV.RATE || '500', 10);
const DURATION = __ENV.DURATION || '60s';

// Pre-seeded by seed.sh (market OPEN, odds 2.0000).
const EVENT = '11111111-1111-7111-8111-111111111111';
const MARKET = '22222222-2222-7222-8222-222222222222';
const SELECTION = '33333333-3333-7333-8333-333333333333';

export const options = {
  scenarios: {
    constant_rps: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 200,
      maxVUs: 2000,
    },
  },
  thresholds: {
    // Goal from sportsbook/CLAUDE.md: p99 < 100 ms, error rate < 0.1 %.
    http_req_failed: ['rate<0.001'],
    'http_req_duration{op:place}': ['p(99)<100', 'p(95)<50'],
  },
};

const placeLatency = new Trend('place_latency_ms', true);
const placeErrors = new Rate('place_errors');

export default function () {
  const body = JSON.stringify({
    userId: uuidv4(),
    slipType: { type: 'SINGLE' },
    selections: [
      { eventId: EVENT, marketId: MARKET, selectionId: SELECTION, odds: 2.0 },
    ],
    stake: { amount: 10000, currency: 'KRW' },
  });

  const res = http.post(`${BASE_URL}/internal/v1/bets`, body, {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': `load-${uuidv4()}`,
    },
    tags: { op: 'place' },
  });

  const ok = check(res, { 'status is 201': r => r.status === 201 });
  placeErrors.add(!ok);
  if (ok) {
    placeLatency.add(res.timings.duration);
  }
}
