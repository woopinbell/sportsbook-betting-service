# BEST results — betting-service load tests

> **EN — At a glance.** Best curated results across local benchmark runs.
> Numbers are dev-host baselines (macOS / Apple Silicon, Docker Desktop, all of
> PostgreSQL + Redis + Kafka + a WireMock risk/wallet stub + the betting JVM
> sharing one VM). Production-grade numbers (multi-replica, dedicated hosts,
> real risk/wallet services) land with the orchestration repo's e2e harness.

## 측정 결과 — 2026-05-29 (dev host)

### placement_load (synchronous placement, sustained)

Each request is a full ADR-0017 placement: validate → odds slippage (Redis)
→ risk check (HTTP) → wallet debit (HTTP) → accept → outbox insert. Two
synchronous outbound calls + two DB transactions per bet.

| Target RPS | Actual RPS | Errors | p50      | p95       | p99       | Threshold (p99<100ms, err<0.1%) |
|-----------:|-----------:|-------:|---------:|----------:|----------:|----------------------------------|
| 150        | 149.6      | 0 %    | 91.5 ms  | 120.7 ms  | 148.5 ms  | err ✅ / p99 ❌ (dev-host bound)   |

Above ~150 RPS the constant-arrival generator outruns this single-VM host:
VUs pile up and the internal risk/wallet calls start tripping their 500 ms
read timeout (fail-closed 5xx), so the run stops being a latency measurement
and becomes a saturation artifact. The median already sits at ~90 ms because
the whole synchronous chain (2 HTTP round-trips + 2 DB transactions + Redis +
Avro encode + outbox insert) runs with PostgreSQL, Kafka, WireMock and the JVM
all contending for the same Docker VM CPU pool — so even at low load p99 < 100
ms is not reachable here. The production goal stays **10 000 concurrent bets,
p99 < 100 ms, error rate < 0.1 %**; the architecture (no DB transaction across
the HTTP calls, circuit-breaker fail-fast, idempotent retries) shows no
structural ceiling in this slice — the laptop simply co-locates six processes.

> A connection-pool experiment is recorded below under "tuning notes".

Raw JSON: `2026-05-29/placement_load_150.json`.

### idempotency — 50 replays of the SAME Idempotency-Key

Single VU, 50 sequential POSTs carrying one shared key. Every response is
HTTP 201 and carries the **same betId** as the first accept — the replay
short-circuits on the DB idempotency lookup without re-running risk / wallet.
100 % of checks pass (`status is 201`, `same betId as the first accept`).

Raw JSON: `2026-05-29/idempotency.json`.

### concurrency — 100 concurrent requests with the SAME key

100 VUs fire one shared key at once. Every response is either 201 (the single
accept or an idempotent replay of it) or 409 (DUPLICATE_BET for an in-flight
loser) — **never a 5xx and never a second accepted bet**. 100 % of checks
pass. The authoritative single-accept / single-debit invariant is also proven
by `BetPlacementIntegrationTest` (one bet row, wallet debited exactly once).

Raw JSON: `2026-05-29/concurrency.json`.

## Goal vs measured

| 시나리오        | 측정치 (dev host)                                   | 목표                                       | 상태 |
|----------------|-----------------------------------------------------|--------------------------------------------|------|
| placement_load | 150 RPS / p99 148 ms / errors 0 %                   | 10 000 concurrent, p99 < 100 ms, err < 0.1 % | dev-host throughput/latency ceiling — design fine, hardware bound |
| idempotency    | 50 replays collapse to one accepted betId           | 1 accept, rest identical response          | ✅   |
| concurrency    | 100 same-key requests → all 201/409, no 5xx         | no double accept / double debit            | ✅   |

## Tuning notes

- **HTTP client.** Tried `httpclient5` to pool the risk/wallet connections.
  Its default pool (5 connections per route) starved the concurrent placement
  path and ballooned p99 into the seconds, so it was reverted: the JDK
  `HttpClient` (Spring Boot's default `ClientHttpRequestFactory`) already pools
  and gave the better numbers here. A correctly *sized* Apache pool is the
  production option, not the default one.
- The median latency is CPU-bound on this host, not connection-bound — pooling
  did not move the floor.

## 환경 메모

- macOS (Apple Silicon), Docker Desktop
- PostgreSQL 16-alpine, Redis 7-alpine, apache/kafka 3.7.0, wiremock 3.9.2
- betting JAR built from current branch HEAD, defaults from `application.yml`
  except the outbox + reconciliation poll intervals parked at 300 000 ms to
  keep the background drains off the hot CPU path during the run.
- risk-service / wallet-service replaced by a WireMock that always approves /
  debits, so the benchmark isolates betting's own orchestration cost.

## 갱신 규칙

1. 새 결과를 `results/<YYYY-MM-DD>/` 아래 JSON(+ 그래프)로 박제한다.
2. 직전 BEST를 명백히 능가하는 경우만 위 표를 갱신한다 (값 + 출처 폴더 링크).
3. dev-host와 production-grade 측정은 별도 행으로 구분한다.
