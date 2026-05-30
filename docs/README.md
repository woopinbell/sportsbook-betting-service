# betting-service — 문서

이 repo의 학습·회고 문서 진입점. (사용자 진입 문서는 최상위 [README.md](../README.md).)

## 구성

- **[commits/](commits/README.md)** — dev 커밋 1개 = 1페이지. 개발 흐름 순서대로 읽으면
  betting-service가 어떻게 조립됐는지 따라올 수 있다. 면접 직전 복습은 `commits/README.md`의
  **L3/L2 빠른 참조**.
- **[reflection/](reflection/retrospective.md)** — post-build 회고.
  - [`retrospective.md`](reflection/retrospective.md) — 5단 회고(무엇을/가설/가설 vs 실제/다시 한다면/남은 한계).
  - [`change-cost.md`](reflection/change-cost.md) — 6~12개월 변경 시나리오의 깨질 위치·복구·비용.

## 읽는 순서 추천

1. 최상위 [README.md](../README.md) — 시스템에서의 위치, 책임, 성능.
2. [commits/000](commits/000.md) → [022](commits/022.md) — Phase 3(접수) 조립 과정.
3. [commits/023](commits/023.md) → [026](commits/026.md) — Phase 4(정산 consumer) 추가.
4. [reflection/retrospective.md](reflection/retrospective.md) — 어디서 시간을 잃었나, 무엇을 미뤘나.

## 이 repo의 핵심 (면접 1순위)

- **동기 orchestration vs 비동기 Saga** (ADR-0017) — [commits/014](commits/014.md)
- **트랜잭션 경계**(외부 HTTP를 DB 트랜잭션 밖으로) — [commits/014](commits/014.md)
- **K-of-N 시스템 베팅 payout** — [commits/008](commits/008.md)
- **멱등성 + reconciliation 보상** — [commits/014](commits/014.md), [commits/018](commits/018.md)
- **BetSettled/BetVoided consume 멱등·상태전이 가드** — [commits/024](commits/024.md), [commits/025](commits/025.md)

> `docs/notes/`는 Phase 2부터 만들지 않는다(2026-05-29 결정). 토픽별 학습은
> `commits/NNN.md` 본문과 "기억/설명 Level" 색인으로 흡수했다.
