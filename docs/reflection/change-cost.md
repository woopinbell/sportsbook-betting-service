# betting-service 변경 비용 시뮬레이션 (change-cost)

> 6~12개월 안에 들어올 만한 변경 요청을 표로 정리. "깨질 위치 / 복구 동선 / 비용"으로
> 본다. betting-service는 도메인 핵심이라, 변경은 대부분 **상품(slip type / 정산 결과)
> 확장**과 **접수 흐름의 일관성 모델**에서 온다.

## 변경 시나리오

| 변경 요청 | 깨질/건드릴 위치 | 복구 동선 | 비용 |
|---|---|---|---|
| **Asian handicap → half-won / half-lost 결과 추가** | `SettlementResult`(shared-protocol enum, 현재 WON/LOST/PUSH/VOID), `Bet.settle`의 단일 payout, V3 `settled_payout_*` 한 쌍 | enum에 HALF_WON/HALF_LOST 추가 → `Bet.settle`이 stake의 절반을 환불/지급하는 분할 payout 처리. betting은 *기록*만 하므로 변경은 작고, 진짜 계산은 settlement-service. 마이그레이션으로 check 제약 확장 | **중간** (shared enum 변경 = 9 repo 동기, 단 betting 코드는 settle 분기 1곳) |
| **Cash out (경기 중 조기 정산)** | 신규 상태전이(ACCEPTED → CASHED_OUT), 라이브 odds read, 부분 payout 계산, wallet credit 흐름 | `BetStatus`에 CASHED_OUT 추가 + 새 엔드포인트 `POST /bets/{id}/cashout` + 현재 odds로 cash-out value 계산 + wallet credit. odds-feed 라이브 구독 필요. 정산과 경합(이미 SETTLED면 거절)하는 가드 | **높음** (새 흐름 + 라이브 odds 의존 + liability 실시간 계산. ADR-0012 재논의) |
| **SGP (Same Game Parlay) — 같은 경기 내 상관 조합** | `BetSlipValidator`의 L1(Same Market)·L2(Same Event) 금지 규칙, `SystemBetCalculator`의 독립 곱 가정 | L2를 "SGP 슬립이면 허용"으로 분기 + correlation rule로 상관 odds 재계산(독립 곱 대신). maxPayout 공식이 selection 독립성을 전제하므로 핵심 변경 | **높음** (검증 규칙 + payout 모델 동시 변경, odds-feed의 상관 odds 제공 전제) |
| **In-play(라이브) 베팅** | 접수 경로 전체의 latency 예산, odds slippage(라이브는 변동 격함), 동기 호출 체인 | ADR-0017의 "수락 판정 동기"는 유지하되, 접수 경로에 bulkhead/논블로킹(WebFlux) 도입 검토. slippage tolerance를 라이브용으로 동적화. 마켓 suspend 빈도 급증 대응 | **높음** (접수 경로 재설계 가능성, ADR-0017 재논의 트리거 명시됨) |
| **베팅 정책을 운영 중 변경 (application.yml → DB config)** | `BettingPolicyProperties`(현재 `@ConfigurationProperties` 정적 바인딩, startup 고정) | ADR-0009가 이미 진화 경로 명시: DB 테이블 + 캐시 + 핫리로드. 검증 로직(`BetSlipValidator`)은 policy 인터페이스를 그대로 받으므로 불변, 바인딩 소스만 교체 | **낮음~중간** (인터페이스 보존, 소스만 DB로. 캐시 무효화 추가) |
| **백그라운드 잡 다중 인스턴스 안전화** | `OutboxPublisher`·`BetReconciliationJob`의 `@Scheduled`(인스턴스마다 같은 행을 집을 수 있음) | `findUnpublished`/`findByStatusAndCreatedAtBefore` 쿼리에 `FOR UPDATE SKIP LOCKED` 추가, 또는 ShedLock으로 leader 1대만 실행. 작업이 이미 멱등이라 정합성은 안전, 중복 작업만 제거 | **중간** (쿼리 + 락 또는 ShedLock 의존 추가. 로직 골격 불변) |
| **인프라 실패에 503 (현재 500)** | `DependencyUnavailableException` → `ErrorCode.INTERNAL_ERROR` 매핑, shared-protocol ErrorCode 카탈로그 | shared에 `SERVICE_UNAVAILABLE`(503) 추가 → 예외 매핑 1곳 교체. gateway/admin의 에러 처리도 새 코드 인지 | **낮음(코드) / shared 변경(9 repo 동기) 비용이 더 큼** |
| **토픽 이름을 shared-protocol 상수로 소유** | `bet.placed.v1`/`bet.settled.v1`/`bet.voided.v1` 문자열이 betting(`BetEventFactory`/`SettlementResultListener`)·gateway·settlement에 분산 정의 | shared-protocol이 토픽 이름 상수 노출 → 각 서비스가 그것을 참조. producer/consumer 불일치를 컴파일 타임에 차단 | **낮음~중간** (상수 도입은 쉬움, 단 9 repo 동기 + Phase 5에서 실제 `.v1` 불일치가 났던 교훈 — change 가치 높음) |

## 의도적으로 미룬 진화

- **베팅 정책 DB config + 핫리로드** — V1은 `application.yml` 정적 바인딩(재시작 필요). 운영팀이 max-stake·slippage를 무중단 조정해야 할 때 DB로(ADR-0009 명시 경로). 인터페이스를 이미 `BettingPolicyProperties`로 추상화해 교체 비용을 낮춰뒀다.
- **exactly-once outbox** — V1은 at-least-once(소비자 멱등으로 흡수). Kafka transactional producer + read-process-write로 exactly-once를 만들 수 있지만, 소비자 멱등이 이미 있어 과설계. 중복이 실제 문제(비멱등 소비자 등장)가 되면.
- **reconciliation을 wallet 상태 조회 기반으로** — V1은 멱등 re-debit이 probe를 겸한다. wallet에 `GET /transactions/by-key`가 생기면 re-debit 없이 조회만으로 roll-forward(호출 1회 절약). wallet API 확장이 선행돼야 함.
- **접수 경로 비동기 논블로킹(WebFlux)** — V1은 servlet + blocking 동기 호출. 동기 호출 체인이 betting 스레드를 고갈시킬 만큼 트래픽이 폭증하면 bulkhead/WebFlux 검토(ADR-0017 재논의 트리거). 단 "수락 판정 동기" 자체는 유지.

## 재설계가 합리적인 임계점

- **접수 스레드 고갈** — 동기 HTTP 체인이 betting의 톰캣 스레드를 다 잡아먹기 시작하면(느린 wallet + 고트래픽), circuit breaker fail-fast만으로 부족해지는 순간이 임계점. 접수 경로에 bulkhead(전용 스레드 풀 격리) 또는 reactive 전환. 단 트랜잭션 경계를 이미 외부 호출 밖에 빼뒀으므로 DB 커넥션 고갈은 이미 방어돼 있다.
- **상품 다양화(SGP / cash out / in-play)가 동시에 들어올 때** — 세 가지 모두 selection 독립성 가정(payout 독립 곱) 또는 "수락 후 상태 고정" 가정을 깬다. 하나씩이면 분기로 흡수되지만 셋이 겹치면 도메인 모델(slip → line → payout)을 라이브·상관·부분정산까지 일반화하는 재설계가 합리적. ADR을 새로 쓸 임계점.
- **단일 운영자 → multi-tenant** — V1은 단일 운영자 가정(ADR-0012). 여러 브랜드/운영자를 한 인스턴스가 처리해야 하면 tenant 차원이 bet·policy·idempotency 키에 전부 들어가야 해 광범위한 재설계.

## 한 줄 요약

betting-service의 핵심 자산은 **접수 흐름의 일관성 구조**(트랜잭션 경계를 외부 호출 밖으로, 멱등 2겹, 보상 안전한 reconciliation)다. 이 골격은 상품을 늘려도(Asian handicap 결과 추가 등) 대부분 *기록 분기*로 흡수돼 비용이 낮다. 진짜 비용이 큰 변경은 **selection 독립성 가정을 깨는 상품**(SGP)과 **"수락 후 고정" 가정을 깨는 상품**(cash out / in-play) — 둘 다 V1이 ADR-0012로 의도적으로 닫아둔 지점이다.
