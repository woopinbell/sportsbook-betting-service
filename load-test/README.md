# betting-service — load & proof tests

> **English summary** — k6 scenarios for the synchronous placement path
> (ADR-0017). The Docker Compose stack runs betting + PostgreSQL + Redis +
> Kafka, with `risk-service` and `wallet-service` replaced by a WireMock that
> always approves / debits, so the benchmark measures betting's own
> orchestration (validate → slippage → risk → wallet → accept → outbox) rather
> than its dependencies. Three scenarios: sustained throughput, idempotent
> replay, and same-key concurrency. Curated results live in
> [`results/BEST.md`](results/BEST.md).

## 무엇을 측정하나

| 시나리오 | 파일 | 목적 |
|---|---|---|
| 처리량/지연 | `scenarios/placement_load.js` | 지속 placement 부하 — 목표 p99 < 100ms, 에러율 < 0.1% |
| 멱등성 | `scenarios/idempotency.js` | 같은 `Idempotency-Key` N회 → 1건만 수락, 응답 동일(betId) |
| 동시성 | `scenarios/concurrency.js` | 같은 키 동시 N요청 → 201/409만, 5xx·중복 수락 0 |

목표 숫자는 상위 [`sportsbook/CLAUDE.md`](../../CLAUDE.md) "테스트 및 증명 전략"
(betting: **1만 동시 베팅, p99 < 100ms, 에러율 < 0.1%**)에서 온다. 정합성
불변식(단일 수락/단일 차감)은 JVM 통합 테스트 `BetPlacementIntegrationTest`가
권위 있게 증명하고, 여기 k6는 HTTP 레벨에서 재확인한다.

## 실행법

```sh
# 1) 빌드 (fat jar; compose가 ../target 을 bind-mount)
cd .. && mvn -DskipTests package && cd load-test

# 2) 스택 기동 (betting + PG + Redis + Kafka + WireMock risk/wallet stub)
docker compose up -d
#    betting 준비 대기:
curl --retry 30 --retry-delay 3 --retry-all-errors -fs \
  http://localhost:58082/actuator/health/readiness

# 3) odds 캐시 시드 (고정 selection의 market OPEN + odds 2.0000)
./seed.sh

# 4) 시나리오 실행 (결과는 results/<date>/ 에 JSON 박제)
mkdir -p results/$(date +%F)
k6 run -e BASE_URL=http://localhost:58082 -e RATE=150 -e DURATION=30s \
  --summary-trend-stats="avg,med,p(95),p(99),max" \
  --summary-export=results/$(date +%F)/placement_load_150.json \
  scenarios/placement_load.js
k6 run -e BASE_URL=http://localhost:58082 scenarios/idempotency.js
k6 run -e BASE_URL=http://localhost:58082 -e VUS=100 scenarios/concurrency.js

# 5) 정리
docker compose down -v
```

포트 매핑(호스트): betting `58082`, PostgreSQL `55432`, Redis `56379`, Kafka
`59092`, WireMock `58080`. 시나리오의 `BASE_URL` 은 `58082` 를 가리킨다.

## 결과

[`results/BEST.md`](results/BEST.md) 에 최고 성적과 환경, 정직한 dev-host
한계 분석을 박제한다. 원시 JSON 은 `results/<날짜>/`.
