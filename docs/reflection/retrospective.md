# betting-service 회고 (post-build retrospective)

> 작성 주체: 에이전트. 사용자는 읽고 학습·암기, 사실 오류·과장 교정.
> 1인칭 회고 톤으로, 실제 git 히스토리·커밋 본문·코드 diff를 1차 자료로 한다.

---

## 1. 무엇을 만들었나

`betting-service`는 스포츠북 시스템의 **핵심 베팅 도메인 서비스**다. 사용자가 제출한 베팅 슬립을 받아 검증하고, 한도(risk)와 잔고(wallet)를 조율해 수락/거절을 **동기로 즉시** 판정한다. 시스템에서 가장 임팩트가 크고 면접에서 가장 많이 다뤄질 repo다. 27개 dev 커밋(`chore(project)` init ~ `test(settlement) consume`)으로 다음을 완성했다.

- **베팅 슬립 도메인** — Single / Multiple / **System(K-of-N)** 을 한 모델로 일반화. STI(Single Table Inheritance, `bet` 한 테이블 + `slip_type` 판별 컬럼)로 모델링하고, 슬립을 N개의 누적(accumulator) **line** 집합으로 본다. System(K-of-N)은 C(N,K)개 line으로 전개되고, 그 위에서 totalStake(지갑 차감액)와 maxPayout(최악 시 지급액)이 한 공식으로 나온다.
- **슬립 검증** — 구조 규칙 L1(Same Market 금지)·L2(Same Event 금지, multi)·L4(max 15)·L5(max total odds 10000), 그리고 odds-feed Redis 캐시에 대한 odds slippage 3% tolerance + market suspended/closed 차단.
- **동기 orchestration (ADR-0017)** — 접수 경로에서 `risk-service /check` → `wallet-service /debit` 를 동기 HTTP로 순차 호출하고 즉시 ACCEPTED/REJECTED를 응답. Resilience4j 타임아웃 + circuit breaker로 느린 의존이 접수 전체를 막지 않게 한다.
- **멱등성** — `Idempotency-Key` 헤더 기준. Redis SETNX는 빠른 경로, `uk_bet_idempotency_key` DB unique constraint가 강한 보증. `betId`를 wallet/risk 멱등 키로 전파해 재시도가 안전하다.
- **Transactional Outbox** — 수락과 같은 트랜잭션에서 `BetPlacedRequested` outbox 행을 기록하고, 스케줄 publisher가 Kafka로 drain(at-least-once). 사후 risk 집계·settlement read model 용.
- **Reconciliation** — wallet 차감은 성공했는데 accept 트랜잭션이 유실된 부분 실패 구간을, 오래된 PENDING bet을 멱등 re-debit으로 roll-forward(ACCEPTED) 또는 roll-back(REJECTED)해 닫는다.
- **REST API** — `POST /internal/v1/bets`, 조회 2종(by id / cursor pagination), 모든 에러를 RFC 7807 `application/problem+json`으로 렌더.
- **정산 consumer (Phase 4)** — `BetSettled`/`BetVoided`(Avro) consume → ACCEPTED 베팅을 SETTLED/VOIDED로 전이. betId 멱등, 상태전이 가드, 불법 입력은 DLT로.
- **부하/증명** — k6 + Docker Compose 스택(WireMock risk/wallet stub)으로 placement 처리량 + 멱등·동시성 정합성을 박제.

검증은 13개 단위/통합 테스트 클래스(Testcontainers PostgreSQL/Redis, @EmbeddedKafka, WireMock)로 박제했다. `mvn verify` 그린, Spotless + Checkstyle 0 위반.

---

## 2. 시작 시점의 가설

- "베팅 접수는 분산 트랜잭션이니 ADR-0006의 비동기 Saga(이벤트 왕복)로 가야 멋지다."
- "System(K-of-N) payout은 조합론이라 복잡하겠지만, single/multiple과는 별개 코드 경로가 될 것이다."
- "동기 HTTP 호출 2개를 붙이는 건 RestClient로 금방 끝난다. 핵심은 호출 자체다."
- "멱등성은 DB unique 하나면 충분하다."
- "정산은 Phase 4의 다른 서비스 일이니 betting은 거의 손댈 게 없다."
- "부하 목표 p99 < 100ms는 dev 박스에서도 한 번 찍어보면 나올 것이다."

이 중 **첫 번째는 ADR-0017로 뒤집혔고**, 나머지는 대부분 "맞지만 실제 어려움은 다른 데 있었다"로 판명됐다.

---

## 3. 가설 vs 실제 — 어디서 실제로 시간을 잃었나

### 3-1. 가장 큰 결정: 접수는 비동기 Saga가 아니라 동기 orchestration (ADR-0017)

Phase 3 진입 직전 가장 크게 멈춰서 고민한 지점. ADR-0006은 "베팅 흐름의 분산 트랜잭션 = Saga + Outbox(비동기)"를 일반 원칙으로 박았는데, 이걸 베팅 *접수*에 그대로 적용하면 안 된다는 걸 두 가지가 드러냈다.

1. **베팅 도메인의 본질**: odds는 초 단위로 변한다. 사용자가 본 odds(1.85)와 접수 시점 odds(1.80)가 다를 수 있고(slippage), 수락 판정이 늦으면 그 사이 odds가 또 변해 "어느 시점 기준으로 수락했나"가 분쟁이 된다. 베팅 버튼 → 즉시 수락/거절이라는 UX도 "접수 중…" 대기를 허용하지 않는다. 즉 수락 판정은 수백 ms 내 **동기**로 나야 한다. 실제 메이저 북메이커(bet365, Pinnacle)의 접수도 동기 critical path다.
2. **Phase 2 leaf의 실태**: wallet-service는 `BetPlacedRequested`를 consume하지 않는다. 동기 REST `/debit`만 노출하고 결과는 사후 outbox 이벤트로 publish한다. risk-service도 동기 `/check`를 노출하고 Kafka consume은 사후 집계용이다. 즉 "완전 비동기 Saga"였다면 wallet/risk에 consumer를 추가하는 재작업이 필요했을 텐데, 이미 동기 API + 사후 이벤트 형태라 동기 orchestration 정석과 정확히 맞물렸다.

→ ADR-0017로 베팅 흐름을 **두 구간**으로 분리했다: 접수(수락/거절)는 동기 orchestration, 사후·정산은 비동기 이벤트(ADR-0006 유지). 이 결정의 근거는 "재작업 회피"가 아니라 **도메인 본질**이고, Phase 2 정합은 결과적 행운이다. **교훈: 아키텍처 원칙(비동기 Saga)을 모든 흐름에 기계적으로 적용하면 도메인을 잘못 모델링한다. "왜 접수를 비동기로?"에 좋은 답이 안 나오는 순간이 신호였다.**

### 3-2. 트랜잭션 경계 — 외부 HTTP를 DB 트랜잭션에 절대 넣지 않기

ADR-0017을 구현하며 실제로 가장 신경 쓴 코드 결정. 동기 호출 체인을 단순하게 짜면 `place()` 전체에 `@Transactional`을 걸고 그 안에서 risk/wallet을 호출하기 쉽다. 그러면 **HTTP 왕복(수백 ms) 동안 풀링된 DB 커넥션을 점유**한다 — 1만 동시 베팅이면 커넥션 풀(HikariCP 20개)이 순식간에 고갈되고, 느린 wallet 하나가 전체 접수를 마비시킨다.

해법은 `BetPlacementService`(orchestrator)를 **`@Transactional`이 아니게** 두고, DB 단계들을 별도 빈 `BetStore`의 짧은 트랜잭션으로 분리한 것이다.

```java
// BetPlacementService.place() — NOT @Transactional
store.savePending(bet);                       // 짧은 트랜잭션 #1 (커밋됨)
riskClient.check(...);                         // HTTP — 트랜잭션 밖
walletClient.debit(...);                       // HTTP — 트랜잭션 밖
store.acceptAndEnqueue(betId, event, now);     // 짧은 트랜잭션 #2 (accept + outbox 원자적)
```

`BetStore`를 별도 빈으로 뺀 데는 두 이유가 있다: (1) 외부 호출을 트랜잭션 사이에 두는 구조를 강제하고, (2) `@Transactional`은 Spring AOP proxy로 동작하므로 **self-invocation(같은 빈의 메서드 호출)이면 트랜잭션이 안 걸린다** — 별도 빈이라야 proxy를 통과한다. C++ 출신 관점에서 이게 처음엔 함정이었다: 메서드에 애너테이션을 붙였다고 항상 트랜잭션이 생기는 게 아니라, **프록시 경계를 넘는 호출**에만 적용된다.

**부작용으로 분산 일관성 구간이 생긴다**: savePending(PENDING 커밋) → wallet debit 성공 → 그런데 acceptAndEnqueue 직전 프로세스가 죽으면, bet은 PENDING인데 돈은 차감된 상태로 남는다. 이게 3-4의 reconciliation을 낳았다.

### 3-3. System(K-of-N) payout — 별개 경로가 아니라 한 공식

가설은 "system은 single/multiple과 다른 코드"였는데, 실제로는 **세 타입이 한 공식의 특수 케이스**로 깔끔하게 떨어졌다. 슬립을 line(누적) 집합으로 보면:

- Single → 1개 선택의 1 line
- Multiple → N개 선택 전부의 1 line
- System(K-of-N) → C(N,K)개 line, 각 K-subset마다 하나

`Bet.stake`를 **per-line(unit) stake**로 정의하니, totalStake(지갑 차감) = unit × lineCount, maxPayout(모든 line이 이김) = unit × Σ(line별 odds 곱). Single/Multiple은 lineCount=1로 같은 공식에서 떨어져 **나눗셈이 없고 FLOOR 반올림 한 번**으로 끝난다. 돈 계산에서 나눗셈/중간 반올림이 없다는 건 정합성 측면에서 큰 이점이다.

실제 시간이 든 건 조합론 자체가 아니라 **경계의 정직한 테스트**였다. C(15,7)=6435 line이 최악인데(L4 max 15가 폭발을 막는다), binomial을 곱셈 공식(`result = result*(n-i)/(i+1)`)으로 계산하면 n≤15에서 long 오버플로 없이 정확하다. 2-of-3이 3개 조합 곱의 합(26×), K=N이 matching multiple과 같음, sub-unit 분수가 floor됨 — 이런 단언을 다 박았다. **돈·조합은 가장 error-prone이고 면접 단골이라 테스트를 가장 두껍게.**

### 3-4. Reconciliation — roll-back에 보상 credit을 넣으면 안 된다

3-2가 만든 부분 실패 구간(PENDING + 차감됨)을 닫는 `BetReconciliationJob`. 오래된 PENDING bet을 찾아 **같은 betId 키로 wallet에 re-debit**한다. wallet에 "키로 조회" 엔드포인트가 없고, debit이 멱등(betId 키)이라 re-debit이 **상태 probe이자 roll-forward를 겸한다**:

- debit 200 → 돈이 (지금 또는 이전에) 잡혀 있음 → roll-forward(ACCEPTED) + outbox emit
- 422 insufficient → 차감이 애초에 안 됐고 될 수도 없음 → roll-back(REJECTED)
- 503 → wallet 불가, 다음 tick으로 미룸(defer)

여기서 실제로 깨달은 함정: **roll-back 시 보상 credit(환불)을 넣으면 안 된다.** 직관적으로는 "취소하니 환불"이지만, 422가 떴다는 건 *차감이 애초에 안 일어났다*는 뜻이다. 게다가 wallet의 `locked` 버킷은 **per-account이지 per-bet이 아니다** — 차감된 적 없는 stake를 환불하면 **다른 베팅이 잡아둔 돈을 끌어다 쓰는** 버그가 된다. 그래서 roll-back은 그냥 REJECTED로 표시만 하고, 환불 primitive(`WalletClient.refund`)는 *확정 차감 후 포기* 흐름(정산/admin)에만 쓴다. 또 reconciliation tick이 여러 번 돌아도 안전하도록 `acceptAndEnqueue`/`markRejected`를 **PENDING 가드**로 멱등화했다(중복 outbox 행 방지).

### 3-5. 비즈니스 거절 vs 인프라 실패 — circuit breaker가 삼키면 안 되는 것

동기 클라이언트(risk/wallet)에서 제일 중요한 설계점. 두 종류의 "실패"를 반드시 구분해야 한다.

- **비즈니스 거절**: risk 한도 초과(approved:false, HTTP 200!), wallet 잔고 부족(HTTP 422 `WALLET_INSUFFICIENT_BALANCE`). 이건 **정상 판정**이다. 사용자에게 REJECTED를 즉답해야 한다.
- **인프라 실패**: 타임아웃, connection refused, 5xx. 이건 의존이 아픈 것 → fail-closed.

함정은 risk가 거절도 **HTTP 200**으로 답한다는 것. `approved:false`를 그냥 두면 정상 응답으로 흘러간다. 그래서 RiskClient가 이를 `RiskLimitException`(비즈니스)으로 변환한다. 그리고 **circuit breaker는 `DependencyUnavailableException`(인프라)만 record하도록** 설정했다(`record-exceptions`에 그것만 등록). 만약 비즈니스 거절도 breaker가 세면, "잔고 부족 사용자가 많을 때 breaker가 열려 멀쩡한 wallet 호출까지 fallback으로 빠지는" 어이없는 일이 생긴다. 거절은 breaker를 절대 트립하지 않고, fallback은 회로가 열렸을 때만 발동한다. 변환을 **메서드 본문 안**에서 하므로 AOP proxy 유무와 무관하게 매핑이 성립한다.

### 3-6. 멱등성은 한 겹이 아니라 두 겹 — 그리고 race의 진실

가설 "DB unique 하나면 충분"은 반은 맞다. DB `uk_bet_idempotency_key`가 **강한 보증**이다. 하지만 그것만으로는 같은 키 100개 동시 요청이 전부 validate→risk→wallet을 다 돌고 나서 99개가 DB에서 깨지는 — 낭비가 크다. 그래서 Redis SETNX를 **빠른 경로**로 앞에 둔다(이미 처리 중이면 일찍 단락). 핵심은 계층의 역할 분담이다: Redis는 latency 최적화, DB가 정합성의 최종 권위. 그리고 validate를 reservation **앞에** 둬서, 잘못된 슬립은 키도 행도 태우지 않는다.

동시성 테스트(같은 키 100개)에서 본 진실: 응답은 **201(단일 수락 또는 그 멱등 replay) 아니면 409(in-flight 패배자 DUPLICATE_BET)뿐, 절대 5xx도 두 번째 수락도 없다.** savePending이 `saveAndFlush`라 중복 키 위반이 *그 자리에서* 터지고(나중 flush가 아니라), 그걸 잡아 이미 커밋된 bet을 반환한다. 단일 수락/단일 차감 불변식은 `BetPlacementIntegrationTest`가 권위 있게 증명한다(bet 행 1개, wallet 정확히 1회 차감).

### 3-7. 정산 consumer (Phase 4) — "거의 손댈 게 없다"는 빗나갔다

가설은 "정산은 settlement-service 일"이었지만, betting도 **소비 측**을 구현해야 했다(ADR-0017 Phase 4). `BetSettled`/`BetVoided`(Avro)를 consume해 ACCEPTED→SETTLED/VOIDED 전이. 실제 신경 쓴 지점:

- **멱등 + 상태전이 가드**: Kafka는 at-least-once라 같은 이벤트가 재배달된다. 이미 SETTLED인 bet에 BetSettled가 다시 오면 **no-op**(idempotent), 그런데 *unknown betId*나 *불법 전이(ACCEPTED 아닌 상태에서 전이)*는 **throw해서 DLT로** 보낸다 — 조용히 상태를 망가뜨리는 대신. "재배달이면 무시, 진짜 이상이면 DLT"의 경계를 명확히.
- **SETTLED vs (SettlementResult=VOID)의 구분**: 슬립 전체가 VOIDED(경기 취소/연기 → 전액 환불, V4 `void_reason`)인 것과, SETTLED인데 한 selection의 결과가 VOID(settle-time 부분 환불)인 것은 **다르다**. 그래서 별도 terminal status로 모델링했다. 이 구분을 놓치면 환불 의미가 뒤섞인다.
- **maxPayout vs settledPayout**: 수락 시 저장한 maxPayout은 *최악 시* 감사용 수치고, 정산 시 기록하는 settledPayout은 *실제 지급액*이다(V3 컬럼 분리). LOST면 settledPayout=0.
- **Avro를 aggregate에 안 새게**: 리스너(edge)에서만 Avro 타입을 다루고 JPA aggregate엔 도메인 값(betting-local `VoidReason` enum 등)만 넘긴다. wire schema가 도메인으로 새지 않게.

### 3-8. 부하 목표 p99 < 100ms는 dev 호스트에서 도달 불가 — 그래도 정합성은 증명

가설 "dev 박스에서 p99 < 100ms 한 번 찍어보면 나올 것"은 빗나갔다. placement 1건은 **동기 HTTP 2회(risk·wallet) + DB 트랜잭션 2개 + Redis + Avro 인코드 + outbox insert**다. 게다가 dev 호스트는 PostgreSQL·Kafka·WireMock·betting JVM이 한 Docker VM의 CPU를 나눠 쓴다. 그래서 **median이 이미 ~90ms**고, 150 RPS / 30s에서 149.6 RPS 지속·에러 0%·p99 148.5ms가 나왔다. 150 RPS를 넘기면 constant-arrival 생성기가 단일 VM을 추월해 VU가 쌓이고 내부 호출이 500ms read timeout을 치기 시작 — latency 측정이 아니라 saturation 아티팩트가 된다.

핵심 판단: **이건 하드웨어 한계지 구조적 천장이 아니다.** 아키텍처(외부 호출을 DB 트랜잭션 밖에, breaker fail-fast, 멱등 재시도)에는 병목이 없다. p99 < 100ms / 1만 동시는 multi-replica·전용 호스트의 production 목표로 남기고(orchestration e2e에서 실측), dev 박스에서는 **정합성 불변식**(멱등 50회 → 단일 betId, 동시 100 → 201/409만)을 증명하는 데 집중했다. tuning 중 httpclient5 풀링을 시도했다가 default 5-per-route 풀이 동시 placement 경로를 starve시켜 p99가 초 단위로 튀어 revert한 것도 박제했다(BEST.md) — 잘못 *크기 잡은* 풀은 default JDK HttpClient보다 나쁘다.

### 시간 배분 요약

"동기 호출 붙이기"가 핵심일 줄 알았는데, 실제 시간은 **(1) 접수=동기 결정(ADR-0017) (2) 트랜잭션 경계를 외부 호출 밖으로 빼는 구조 (3) reconciliation의 보상 안전성 (4) 비즈니스/인프라 실패 분리 (5) 정산 consumer 멱등·상태전이**에 들어갔다. 베팅 서비스의 난이도는 "HTTP를 부르느냐"가 아니라 **"부분 실패와 재시도에서 돈과 상태가 어떻게 깨지는가"**를 다루는 데 있었다.

---

## 4. 다시 한다면

- **접수 일관성 모델을 도메인부터 본다.** "분산 트랜잭션이니 Saga"라는 일반 원칙을 접수에 기계 적용하지 않고, odds staleness·즉답 UX를 먼저 보면 ADR-0017이 처음부터 나온다. (실제로 그렇게 했지만, ADR-0006을 쓸 때 "접수는 예외"를 미리 적어둘 수 있었다.)
- **트랜잭션 경계를 가장 먼저 설계한다.** "어디서 커밋하고 어디서 HTTP를 부르나"를 코드보다 먼저 그리면, BetStore 분리·self-invocation 함정을 처음부터 피한다.
- **보상(compensation)의 안전성을 wallet의 버킷 모델(locked는 per-account)과 함께 본다.** roll-back에 환불을 안 넣는다는 판단은 wallet 내부를 알아야 나온다 — 의존 서비스의 데이터 모델을 먼저 읽는다.
- **circuit breaker의 record-exceptions를 처음부터 인프라 예외로 좁힌다.** "200인데 거절"인 risk 응답을 비즈니스 예외로 일찍 분리.
- **부하는 처음부터 "dev 호스트 정합성 + production 규모(전체 스택)"로 2층 설계.** 단일 VM으로 p99 < 100ms / 1만 동시를 흉내 내려 하지 않는다.
- **정산 consumer를 Phase 3 설계 시점에 "betting도 소비 측이 있다"로 명시.** Phase 4에서 "거의 없을 것"이라 가정하지 않는다.

---

## 5. 남은 한계 (의도적으로 닫지 않은 범위)

전부 ADR-0008 / 0012의 V1 scope 결정과 일치하며, 의도적으로 열어둔 것이다.

- **SGP (Same Game Parlay) / Bet Builder 미지원** — L1(Same Market)·L2(Same Event)가 multi에서 같은 경기·마켓 조합을 *금지*한다. SGP는 같은 경기 내 상관 selection을 조합하는 상품이라, correlation rule(상관 odds 재계산)이 필요한데 V1 제외(ADR-0008 / 0012). 도입하려면 검증 규칙과 payout 모델을 함께 바꿔야 한다.
- **Cash out 미지원** — 경기 진행 중 조기 정산. 라이브 odds·부분 정산·실시간 liability 계산이 필요(ADR-0012 V1 제외). 현재 베팅은 수락 후 정산까지 상태가 고정이다.
- **In-play(라이브) 베팅 미지원** — 접수 latency 요구가 더 빡빡하고 odds 변동이 더 격해 접수 경로 재설계가 필요(ADR-0012). ADR-0017도 in-play 도입을 재논의 트리거로 명시.
- **서버사이드 베팅 카트 미지원** — 카트 상태는 프론트가 관리, betting은 완성된 슬립만 받는다(ADR-0012).
- **Half-won / Half-lost (Asian handicap) 미지원** — SettlementResult는 WON/LOST/PUSH/VOID 4종뿐(ADR-0013). half-* 결과는 Asian handicap 마켓을 추가할 때.
- **DependencyUnavailableException → 500** — shared ErrorCode 카탈로그에 V1 `SERVICE_UNAVAILABLE`가 없어 인프라 실패를 INTERNAL_ERROR(500)로 매핑한다. 의미상 503이 더 맞지만 V1 카탈로그 한계. ErrorCode에 503을 추가하면 정정된다.
- **reconciliation은 wallet "키 조회" 부재를 re-debit으로 우회** — wallet에 `GET /by-idempotency-key`가 있으면 re-debit 없이 상태만 조회해 roll-forward할 수 있다. 현재는 멱등 re-debit이 probe를 겸하는 설계(안전하지만 wallet에 호출 1회 더).
- **single-replica 가정의 백그라운드 잡** — outbox publisher·reconciliation 잡이 `@Scheduled`다. 다중 인스턴스로 띄우면 같은 행을 여러 인스턴스가 집을 수 있다(작업은 멱등이라 정합성은 안전하나 중복 작업). production 다중화 시 shard/leader election 또는 `SELECT ... FOR UPDATE SKIP LOCKED` 기반 큐가 필요.
- **at-least-once outbox의 중복 발행** — publisher가 ack 전 죽으면 같은 행을 다음 tick에 재전송한다. 소비자(risk/settlement/betting 자신)가 전부 멱등이라 안전하지만, exactly-once는 아니다(V1 의도).
