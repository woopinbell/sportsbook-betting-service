# betting-service

> **English summary**
>
> **What it is.** `betting-service` is the core bet-placement domain service of
> the sportsbook microservice system — the highest-impact repo in the system.
> It accepts user bet slips (Single, Multiple, and System / K-of-N), validates
> their structure and live odds, decides accept/reject **synchronously**, and
> later applies the asynchronous settlement outcome onto each accepted bet.
>
> **Architecture — placement (synchronous, ADR-0017).** Bet placement is a
> synchronous orchestration, not an async Saga: odds move by the second and the
> user must see accepted/rejected immediately, so the verdict has to return
> within one request (target p99 < 100 ms). The flow is: validate the slip
> (L1/L2/L4/L5) → check odds slippage against the `odds-feed-service` Redis
> cache (3 % tolerance) → persist the bet PENDING → call `risk-service`
> (`/check`) then `wallet-service` (`/debit`) over synchronous HTTP → accept (or
> reject) and return. The orchestrator is deliberately **not** `@Transactional`:
> the DB steps run as short transactions and the HTTP calls happen strictly
> between them, so a pooled DB connection is never held across a network
> round-trip. The risk/wallet calls are guarded by Resilience4j timeouts + a
> circuit breaker that records only infrastructure failures, so a business
> decline (limit exceeded / insufficient balance) never trips the breaker.
>
> **Architecture — post-placement & settlement (asynchronous, ADR-0006).** On
> acceptance a `BetPlacedRequested` event is written to a transactional outbox
> in the same transaction (so the event can never diverge from the acceptance)
> and drained to Kafka by a scheduled publisher, feeding risk aggregation and
> the settlement read model. After a match result, `settlement-service`
> publishes `BetSettled` / `BetVoided` (Avro); a Kafka listener here applies the
> terminal transition (ACCEPTED → SETTLED / VOIDED), idempotent by `betId`.
>
> **Consistency.** Placement idempotency is keyed by the `Idempotency-Key`
> header — a Redis SETNX fast path plus a DB unique constraint as the strong
> guard — and `betId` is propagated as the wallet/risk idempotency key so a
> retry never double-charges. A reconciliation job closes the partial-failure
> window (wallet debited but the accept transaction lost) by rolling stale
> PENDING bets forward (re-debit succeeds → ACCEPTED) or back (insufficient →
> REJECTED), with no compensating credit on roll-back because the debit never
> landed.
>
> **Position.** Depends on `shared-protocol` (library) plus `wallet-service`,
> `risk-service`, and `odds-feed-service` at runtime; called by `gateway`;
> consumed from by `settlement-service` and `risk-service` (Kafka).
>
> **Tech stack.** Java 17, Spring Boot 3.2, Maven. PostgreSQL 16 + Flyway (STI
> `bet` + `bet_leg`, single-table inheritance over the slip type). Redis (odds
> cache read + idempotency SETNX). Kafka + Avro, binary, no schema registry in
> V1 (consumers pin the shared-protocol classes). Resilience4j. Micrometer /
> OpenTelemetry / Prometheus. Tested with JUnit5 + AssertJ + Mockito +
> Testcontainers + WireMock + embedded Kafka.
>
> **Build & run.** `mvn verify` runs Spotless, Checkstyle and the full suite;
> integration tests use Testcontainers, so Docker must be running, and
> `shared-protocol` must be installed to mavenLocal first
> (`cd ../shared-protocol && mvn install`).
>
> **Performance.** Target: 10 000 concurrent bets, p99 < 100 ms, error rate
> < 0.1 %. Dev-host baseline (Docker Desktop, PostgreSQL + Redis + Kafka + a
> WireMock risk/wallet stub + the betting JVM all sharing one VM): 150
> placements/s sustained at p99 148 ms with 0 % errors; 50 idempotent retries
> collapse to a single accepted bet; 100 same-key concurrent requests return
> only 201/409 and never double-accept. p99 < 100 ms / 10k concurrency is a
> production (multi-replica, dedicated hosts) target — each bet is two
> synchronous HTTP round-trips plus two DB transactions, and the dev host
> co-locates six processes on one CPU pool — not a structural ceiling. See
> [`load-test/results/BEST.md`](load-test/results/BEST.md).
>
> **Limitations (V1).** No SGP / Bet Builder, no cash out, no in-play betting,
> no server-side bet cart, no half-won / half-lost (Asian handicap) results.
> Infrastructure-unavailable maps to 500 (no `SERVICE_UNAVAILABLE` in the V1
> error catalog). See ADR-0005 / 0006 / 0008 / 0009 / 0012 / 0013 / 0017 in
> `orchestration/docs/architecture/decisions/`.

---

## 시스템에서의 위치

`betting-service`는 9개 repo로 구성된 sportsbook 시스템의 **핵심 베팅 도메인
서비스**다. 사용자가 제출한 베팅 슬립을 받아 검증하고, 한도(risk)와 잔고(wallet)를
동기로 조율해 수락/거절을 즉시 판정한다. 시스템에서 가장 임팩트가 크고 면접에서
가장 많이 다뤄질 repo다.

```
┌──────────────────────────────────────────────────────────────────────┐
│ shared-protocol  ←── betting-service (this repo)                      │
│                                                                        │
│ [접수 — 동기, ADR-0017]                                                │
│ gateway ──→ betting ──HTTP──→ risk-service   (/check, 동기)            │
│                  │  ──HTTP──→ wallet-service (/debit, 동기)            │
│                  │  ──Redis read──→ odds-feed-service (slippage)       │
│                  └──outbox──→ Kafka: BetPlacedRequested (사후)         │
│                                  └──→ risk(집계) / settlement(read model)│
│                                                                        │
│ [정산 — 비동기, ADR-0006 / Phase 4]                                    │
│ settlement-service ──Kafka──→ betting (BetSettled/BetVoided consume)   │
│                                  └──→ bet SETTLED / VOIDED 전이         │
└──────────────────────────────────────────────────────────────────────┘
```

상세 의존성 그래프와 cross-cutting 결정은 상위 폴더의
[`sportsbook/CLAUDE.md`](../CLAUDE.md) 와 ADR 디렉터리를 참조한다. 개발 과정·회고는
[`docs/`](docs/README.md)(커밋별 문서 + reflection)에 정리돼 있다.

## 책임 범위

**한다**:

- 베팅 슬립 접수 API (`POST /internal/v1/bets`)
- 슬립 구조 검증 — L1(Same Market 금지), L2(Same Event 금지, multi), L4(max 15),
  L5(max total odds) — [ADR-0008](../orchestration/docs/architecture/decisions/0008-betting-domain-model.md)
- odds slippage 검증 (odds-feed Redis 캐시 read, 3% tolerance) + market
  suspended/closed 차단
- System(K-of-N) 조합 생성 + max payout 계산
- **멱등성 처리** (`Idempotency-Key` 헤더 — Redis SETNX + DB unique constraint,
  `betId`를 wallet/risk 멱등 키로 전파)
- 동기 orchestration — risk check → wallet debit 순차 호출, 즉각 수락/거절
  ([ADR-0017](../orchestration/docs/architecture/decisions/0017-bet-placement-sync-orchestration.md))
- 베팅 수락 후 outbox로 `BetPlacedRequested` publish (사후 집계/정산용)
- reconciliation — wallet 차감 성공 후 accept 유실로 PENDING에 박힌 베팅을
  멱등 re-debit으로 roll-forward / roll-back (ADR-0017 보상)
- 정산 결과 consume — settlement-service의 `BetSettled` / `BetVoided`(Kafka)를
  받아 ACCEPTED 베팅을 `SETTLED` / `VOIDED`로 전이 (betId 멱등, ADR-0006)
- 베팅 상태 조회 API

**하지 않는다**:

- 잔고 조회/변경 직접 수행 (wallet-service 책임)
- 베팅 결과 *판정* + payout *산정* (settlement-service 책임) — betting은 그 결과를
  consume해 상태/실제 payout을 *기록*만 한다
- 환불 실행 (wallet-service 책임) — betting은 VOIDED 사유·상태만 기록
- odds 계산/관리 (odds-feed-service 책임)

## 빌드 / 실행 / 테스트

```sh
# shared-protocol 먼저 mavenLocal 설치 (의존 라이브러리)
cd ../shared-protocol && mvn install && cd -

# 컴파일
mvn compile

# 전체 검증 (Spotless + Checkstyle + 테스트). Docker 필요 (Testcontainers).
mvn verify

# 포맷 자동 적용
mvn spotless:apply
```

로컬 실행은 PostgreSQL/Redis/Kafka가 필요하다. 환경변수
(`BETTING_DB_URL`, `BETTING_REDIS_HOST`, `BETTING_KAFKA_BOOTSTRAP`,
`BETTING_HTTP_PORT` 기본 8082)로 엔드포인트를 주입한다.

## 노출 인터페이스

- HTTP REST (`/internal/v1`):
  - `POST /internal/v1/bets` — 슬립 접수 (`Idempotency-Key` 헤더 필수)
  - `GET  /internal/v1/bets/{id}` — 베팅 상태 조회
  - `GET  /internal/v1/bets?userId=&cursor=&limit=` — 베팅 내역 (cursor pagination)
- Kafka publish (사후, outbox): `BetPlacedRequested` (`bet.placed.v1`)
- Kafka consume (정산, Phase 4): `BetSettled` (`bet.settled.v1`),
  `BetVoided` (`bet.voided.v1`) → `SETTLED` / `VOIDED` 전이

## 제한 사항 (V1)

SGP / Bet Builder, cash out, in-play(라이브) 베팅, 서버사이드 베팅 카트,
half-won / half-lost(Asian handicap) 결과는 V1
미지원([ADR-0008](../orchestration/docs/architecture/decisions/0008-betting-domain-model.md),
[ADR-0012](../orchestration/docs/architecture/decisions/0012-v1-scope-decisions.md)).
정산 결과 *판정·payout 산정*은 settlement-service 책임이고, betting은 그 결과
(`BetSettled` / `BetVoided`)를 consume해 상태와 실제 payout을 *기록*만 한다.
인프라 불가(타임아웃/5xx)는 V1 ErrorCode 카탈로그에 `SERVICE_UNAVAILABLE`가 없어
500으로 매핑된다. 더 깊은 한계·변경 비용은
[`docs/reflection/`](docs/reflection/retrospective.md) 참조.

## 성능

목표: **1만 동시 베팅, p99 < 100ms, 에러율 < 0.1%**, 멱등 재요청 1건 수락,
동시 race 중복 차감 0건. 측정·분석 전문은
[`load-test/results/BEST.md`](load-test/results/BEST.md).

**dev-host baseline** (Docker Desktop, PostgreSQL·Redis·Kafka·WireMock(risk/
wallet stub)·betting JVM이 한 VM에서 CPU 공유):

| 시나리오 | 측정치 | 목표 | 상태 |
|---|---|---|---|
| placement_load | 150 RPS 지속, p50 91.5ms / p95 120.7ms / **p99 148.5ms**, 에러 0% | 1만 동시, p99 < 100ms, 에러 < 0.1% | 에러 ✅ / p99 ❌ (dev-host CPU 한계) |
| idempotency | 같은 키 50회 → **단일 betId**로 수렴 | 1건만 수락, 나머지 동일 응답 | ✅ |
| concurrency | 같은 키 100 동시 → 201/409만, 5xx·중복수락 0 | 중복 차감 0 | ✅ |

placement는 베팅 1건당 **동기 HTTP 2회(risk·wallet) + DB 트랜잭션 2개**라
median이 이미 ~90ms고, 6개 프로세스가 한 Docker VM의 CPU를 나눠 쓰는 환경상
dev-host에서 p99 < 100ms는 도달 불가다. 아키텍처(외부 호출을 DB 트랜잭션 밖에
두고, 서킷브레이커 fail-fast, 멱등 재시도)에는 구조적 병목이 없으며, p99 < 100ms /
1만 동시는 multi-replica·전용 호스트의 production 목표다. 정합성 불변식(단일
수락/단일 차감)은 JVM 통합 테스트 `BetPlacementIntegrationTest`가 권위 있게 증명.
