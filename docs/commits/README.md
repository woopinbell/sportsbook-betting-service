# betting-service — 커밋 문서 목차

dev 커밋 1개 = 문서 1페이지. 개발 흐름 단위로 읽는다. (retrospective 커밋
`docs(reflection)`/`docs(commits)`/`docs(readme)` 자체는 문서화 대상이 아니다.)

| # | 커밋 | 한 줄 |
|---|---|---|
| [000](000.md) | `chore(project): initialize betting-service layout` | 스택/컨벤션 골격 + CLAUDE.md 제외 |
| [001](001.md) | `feat(domain): introduce Bet aggregate + BetLeg (STI slip model)` | STI 슬립 모델, 불변식 vs 정책 분리 |
| [002](002.md) | `test(domain): cover Bet/BetLeg invariants` | aggregate 불변식 단위 검증 |
| [003](003.md) | `build(flyway): add V1__bet_and_leg schema` | bet+bet_leg 스키마, 멱등 unique, ddl validate |
| [004](004.md) | `refactor(test): scope Bet persistence test to a JPA slice` | `@DataJpaTest` slice로 결합 끊기 |
| [005](005.md) | `feat(validation): add slip structural validator with policy` | L1/L2/L4/L5 + stake, 비즈니스 예외 분리 |
| [006](006.md) | `feat(validation): add odds slippage checker over the odds-feed cache` | 3% slippage, 사용자 보호적, cross-mult |
| [007](007.md) | `test(validation): cover slip rules and slippage boundaries` | 경계(floor 1.9400 등) 박제 |
| [008](008.md) | `feat(domain): add SystemBetCalculator for K-of-N combinations + payout` | **K-of-N 한 공식 payout** |
| [009](009.md) | `test(domain): prove System bet combinations and payout` | 조합·payout 알려진 값 증명 |
| [010](010.md) | `feat(client): add synchronous risk + wallet clients with Resilience4j` | **비즈니스 vs 인프라 실패 분리** + breaker |
| [011](011.md) | `test(client): WireMock coverage for risk/wallet response translation` | 응답 변환 WireMock 검증 |
| [012](012.md) | `build(flyway): add V2 transactional outbox schema` | outbox 테이블, dual-write 회피 |
| [013](013.md) | `feat(outbox): transactional outbox write side for BetPlacedRequested` | Avro write side, **partition key=userId** |
| [014](014.md) | `feat(placement): synchronous bet-placement orchestration (ADR-0017)` | **트랜잭션 경계 + 멱등 2겹 (핵심)** |
| [015](015.md) | `test(placement): integration coverage for the placement flow` | happy/거절/멱등 실 인프라 검증 |
| [016](016.md) | `feat(outbox): scheduled Kafka publisher for the outbox` | at-least-once drain |
| [017](017.md) | `test(outbox): publisher drains to embedded Kafka` | 실 브로커 drain 검증 |
| [018](018.md) | `feat(reconciliation): resolve stale PENDING bets (ADR-0017 compensation)` | **보상 roll-fwd/back, 환불 금지 함정** |
| [019](019.md) | `test(reconciliation): roll-forward / roll-back / defer` | 세 분기 검증 |
| [020](020.md) | `feat(api): bet REST endpoints + RFC 7807 problem responses` | 접수/조회 + problem+json |
| [021](021.md) | `test(api): web-layer + problem-detail coverage` | web slice 계약 검증 |
| [022](022.md) | `test(load): ... baseline` (+ `docs(readme): performance`) | 부하/증명 + 성능 박제 |
| [023](023.md) | `build(deps): bump shared-protocol to 0.2.0-SNAPSHOT` | (Phase 4) 정산 이벤트 family 도입 |
| [024](024.md) | `feat(settlement): consume BetSettled, flip an accepted bet to SETTLED` | **BetSettled 멱등·전이 가드** |
| [025](025.md) | `feat(settlement): consume BetVoided, flip an accepted bet to VOIDED` | **VOIDED vs SettlementResult.VOID 구분** |
| [026](026.md) | `test(settlement): cover kafka consume, idempotency, and transition guards` | consume·멱등·전이 삼중 검증 |

> [023]~[026]은 **Phase 4(정산 consumer 추가)** 의 dev다. Phase 3 retrospective를 reset → 정산 dev → retrospective 재작성한 흐름의 결과(sportsbook/CLAUDE.md "reset 패턴").

---

## L3 빠른 참조 (외워서 설명 — 면접 직전)

- **왜 접수는 비동기 Saga가 아니라 동기 orchestration인가** [014/ADR-0017] — odds는 초 단위로 변해 수락 판정이 늦으면 분쟁(slippage), 베팅 UX는 즉답. risk·wallet은 강한 일관성(돈·한도). 정산만 비동기(ADR-0006). 실제 메이저 북메이커도 동기.
- **왜 외부 HTTP를 DB 트랜잭션에 안 넣나** [014] — `place()`에 `@Transactional`을 걸면 risk/wallet 왕복 동안 DB 커넥션 점유 → 1만 동시면 풀 고갈. orchestrator는 비트랜잭션, DB는 `BetStore` 짧은 트랜잭션으로 쪼개 HTTP를 사이에. `BetStore` 별도 빈 = 경계 강제 + `@Transactional` 프록시 self-invocation 함정 회피.
- **멱등 2겹** [014] — Redis SETNX(가속) + DB `uk_bet_idempotency_key`(권위). validate를 reservation 앞에. saveAndFlush가 중복 즉시 터뜨려 커밋된 bet 반환. betId 미리 생성 = wallet 멱등 키.
- **K-of-N payout 한 공식** [008] — 슬립=line 집합, System은 C(N,K) line. totalStake=unit×lineCount, maxPayout=unit×Σ(line별 odds 곱). Single/Multiple은 lineCount=1 → 나눗셈 없이 FLOOR 한 번(보수적). binomial 곱셈 공식 n≤15 안전, L4가 C(15,7)=6435 상한.
- **circuit breaker가 비즈니스 거절을 세면 안 됨** [010] — risk 거절은 HTTP 200(approved:false), wallet은 422. 인프라 실패(timeout/5xx)와 타입 분리, breaker record는 `DependencyUnavailableException`만. 안 그러면 잔고부족 트래픽이 회로 열어 멀쩡한 의존 fallback.
- **reconciliation 보상** [018] — wallet debit 성공 후 accept 유실 → PENDING+차감. 멱등 re-debit(betId 키): 200=roll-forward, 422=roll-back(REJECTED), 503=defer. **roll-back에 환불 금지** — 422면 차감 안 됐고 wallet locked는 per-account라 환불=남의 돈. tick 멱등(PENDING 가드).
- **outbox가 at-least-once인 이유** [012/016] — DB저장+Kafka발행 따로면 dual-write 불일치. outbox는 이벤트를 수락과 같은 트랜잭션에 DB 행으로, publisher가 ack 후 stamp(실패는 다음 tick). 중복은 소비자 멱등이 흡수.
- **partition key=userId(eventId 아님)** [013] — 슬립은 다중 경기라 단일 eventId 없음, 소비자(risk 윈도우/settlement)가 per-user 순서 원함. ADR-0006 eventId 규칙은 per-match 스트림용.
- **BetSettled consume 멱등·전이 가드** [024] — Kafka at-least-once 재배달. 이미 SETTLED면 no-op(betId), unknown/불법 전이(ACCEPTED 아님)는 throw→DLT(조용한 손상 대신 신호). maxPayout(수락 시 최악)≠settledPayout(실제). Avro는 edge에서만.
- **VOIDED vs SettlementResult.VOID** [025] — VOIDED는 경기 취소/연기로 슬립 전체 void+전액 환불(별도 status). SETTLED+VOID는 selection 부분 환불. 별도라야 환불 의미 안 섞임. 환불은 wallet, betting은 상태/사유만.
- **RFC 7807 + ErrorCode** [020] — 비즈니스 예외가 ErrorCode 운반 → advice 하나가 problem+json(ODDS_DRIFT 409/LIMIT_EXCEEDED 403/INSUFFICIENT_BALANCE 409/EVENT_CLOSED 422). cursor pagination은 UUID v7 keyset, limit+1로 count 없이 hasMore.
- **dev-host에서 p99<100ms가 안 나오는 이유** [022] — 베팅 1건=동기 HTTP 2+DB 2+Redis+Avro+outbox, 6프로세스 1 VM CPU 공유 → median ~90ms. 하드웨어 한계지 구조 천장 아님. 정합 불변식(멱등 50→1, 동시 100→201/409만)은 dev 증명, 규모는 production.

## L2 빠른 참조 (문서 보며 설명)

- BOM으로 Spring Boot 버전 정렬, CLAUDE.md gitignore [000]
- sealed `BetSlipType` ↔ 평면 `SlipKind`+컬럼 복원, maxPayout을 입력으로 [001]
- ddl-auto:validate가 매핑 불일치를 부팅에서 잡음, UUID v7 cursor 겸용 [003]
- `@DataJpaTest` slice + persistence context 1차 캐시(flush+clear) [004]
- `@ConfigurationProperties` 타입 안전 + startup 검증, L1을 L2보다 먼저 [005]
- 두 Redis 키(market/odds), cross-multiplication 경계 [006]
- combinations pruning, `longValueExact` 절단 방지 [008]
- 변환을 메서드 본문에서(proxy 무관), refund 별도 키, fallback은 open circuit만 [010]
- 부분 인덱스(`WHERE published_at IS NULL`), payload BYTEA=Avro [012]
- Avro SpecificRecord binary, Schema Registry 생략(클래스 pin) [013]
- 거절 시 PENDING→REJECTED+rethrow, UTC Clock, PENDING+차감 구간 남김 [014]
- `@Scheduled fixedDelay`, `send().get()` 블로킹 ack, 테스트 주차 [016]
- `@EmbeddedKafka` 인메모리 실 브로커 [017]
- 보상=2PC 대신 멱등+수렴, re-debit이 키 조회 겸용 [018]
- eager leg fetch가 LazyInitialization 회피, correlationId=MDC [020]
- `@WebMvcTest` slice가 MVC만, 거절을 status/content-type/errorCode로 [021]
- WireMock 의존 격리, constant-arrival 150 RPS 넘으면 saturation [022]
- `-SNAPSHOT`/mavenLocal [023]
- V3 check 제약, autoStartup 주차, DLT는 retry 소진 후 [024]
- V4 void_reason check, resolved_at 재사용, Avro enum→도메인 enum [025]
- 멱등 no-op을 `@Version` 불변으로 검증 [026]

## 개발 중 실제로 막혔던 지점 (회고 연결 — `../reflection/retrospective.md`)

- 접수=동기 결정(ADR-0017): "비동기 Saga" 일반 원칙 vs 베팅 도메인 본질 [014]
- 트랜잭션 경계를 외부 HTTP 밖으로 + `@Transactional` self-invocation 함정 [014]
- 멱등 2겹의 race 진실(savePending saveAndFlush가 즉시 터뜨림) [014/015]
- reconciliation roll-back에 환불 넣으면 남의 돈(locked=per-account) [018]
- 비즈니스 거절(risk 200/wallet 422)을 인프라 실패와 분리 + breaker record 좁히기 [010]
- 정산 consumer "거의 없을 것" 가설 빗나감 — betId 멱등·전이 가드·VOIDED 구분 [024/025]
- dev-host p99<100ms 하드웨어 한계 + httpclient5 풀 starve revert [022]
