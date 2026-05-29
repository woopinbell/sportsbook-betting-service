#!/usr/bin/env bash
# Seeds the odds-feed cache (in the redis container) for the fixed selection the
# k6 scenarios bet on: market OPEN + current odds 2.0000 so the slippage check
# passes. Run after `docker compose up` and before the k6 scenarios.
set -euo pipefail

EVENT=11111111-1111-7111-8111-111111111111
MARKET=22222222-2222-7222-8222-222222222222
SELECTION=33333333-3333-7333-8333-333333333333

cd "$(dirname "$0")"
docker compose exec -T redis redis-cli SET "market:${EVENT}:${MARKET}" OPEN
docker compose exec -T redis redis-cli SET "odds:${EVENT}:${MARKET}:${SELECTION}" 2.0000
echo "Seeded market OPEN + odds 2.0000 for ${EVENT}/${MARKET}/${SELECTION}"
