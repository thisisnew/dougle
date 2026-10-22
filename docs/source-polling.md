# Source 자동 갱신 — 쉬운 설명

등록한 자료를 앱이 주기적으로 다시 읽는 기능입니다. **처음에는 OFF**입니다.
Source 아래에서 자동 갱신을 체크하고 주기(60~86400초)를 입력한 뒤 **갱신 설정 저장**을 누르세요.
체크만으로는 시작하지 않습니다. 첫 수집은 저장한 시각에서 한 주기가 지난 뒤입니다.

## 작동 원리

`설정 저장 → 다음 시각 보관 → 시간이 된 Source 선택 → 수집 → 변경 파일만 분석 → 검색 갱신`

- 앱이 켜져 있는 동안 모든 Workspace의 ON Source를 확인합니다. 선택한 Workspace에만 한정하지 않습니다.
- 자동 수집은 동시에 최대 2개입니다. 밀린 횟수를 모두 재생하지 않고 Source당 한 번씩 처리합니다.
- 수동 동기화와 자동 동기화가 같은 Source를 동시에 실행하지 못하도록 DB에서 선점합니다.
- 성공하면 완료 시각부터 다음 주기를 계산합니다. 실패하면 이전 원본을 유지하고 오류를 표시합니다.
- 실패 때 대기 시간을 2배씩 늘립니다. 최대 24시간이며 성공하면 원래 주기로 돌아갑니다.
- OFF는 새로운 수집을 막습니다. 이미 시작한 수집은 완료할 수 있습니다.
- 앱을 다시 켜도 설정·다음 시각을 유지합니다. 이미 지난 예약은 앱 시작 후 처리합니다.
- PC가 잠들거나 앱이 꺼져 있으면 수집하지 못합니다. 정확한 시각 실행을 보장하는 알람 기능이 아닙니다.

**AI 호출·외부 LLM 전송·지식 자동 게시를 하지 않습니다.** 사람이 편집한 지식은 덮어쓰지 않습니다.
로컬 원본이 바뀌면 기존 근거의 CURRENT/STALE/MISSING 판정이 달라집니다.
URL/GitHub는 ON으로 저장하면 사용자 개입 없이 해당 공개 서버에 읽기 요청을 보냅니다.
GitHub는 후속 [변경 blob 수집](git-incremental.md)을 적용합니다. URL은 [조건부 요청](url-conditional.md)을 적용합니다.

## 모델과 실제 코드 대조

| 모델 | 실제 구현 |
|---|---|
| 설정·다음 시각·실패 횟수 | SQLite v19 `source`의 `polling_enabled`, `poll_interval_seconds`, `next_poll_at`, `consecutive_failures`, `polling_revision` |
| 충돌 방지 | `JdbcSourceStore.configurePolling`의 예상 revision 비교; 다른 창이 먼저 저장하면 409 |
| 예약 선점 | `JdbcSourceStore.beginPoll`의 ON/시간/상태 조건을 한 UPDATE에서 확인 |
| 제한된 작업 실행 | `SourcePolling`: 2개 허용, 대기 작업 무한 적재 없음, 종료 시 인터럽트 |
| 원자적 완료 | `JdbcSourceStore.publish`: 스냅샷·분석·다음 예약을 같은 트랜잭션에서 반영 |
| 화면 | `SourcePollingSettings`, `SourcePanel`: 명시적 저장, ON/수집 중이면 3초마다 상태 조회 |

`nextPollAt`은 UTC epoch 밀리초이며 예약이 없으면 null입니다. 화면은 PC 현지 시각으로 표시합니다.
`consecutiveFailures`는 최대 16으로 제한됩니다. 설정을 다시 저장하면 실패 횟수를 초기화합니다.

## API

`PATCH /api/workspaces/{workspaceId}/sources/{id}/polling`

```json
{"enabled":true,"intervalSeconds":3600,"expectedRevision":0}
```

기존 Source 응답에 `nextPollAt`, `consecutiveFailures`, `pollingRevision`을 추가했습니다.
잘못된 값 400, 없는 Source/다른 Workspace 404, 설정 충돌 409입니다.
기존 로컬 변경 헤더와 설치형 세션 인증 규칙을 그대로 적용합니다.

## 검증 명령

2026-10-22 실행 결과: 전체 백엔드 324개 통과/실제 AI opt-in 1개 skip, UI 188개 통과, 실행 JAR 빌드 성공.
실제 60초 예약 수집·OFF·재시작 보존 smoke와 기존 전체 smoke가 통과했습니다.
브라우저에서 ON/다음 예약 표시와 59초 저장 차단을 확인했습니다. 실제 AI/원격 서버 호출은 하지 않았습니다.
테스트는 가상 시계로 마감 경계·백오프·설정 CAS·수동/자동 선점·삭제/격리를 검증하고,
실제 작업자로 동시 실행 상한·종료 인터럽트를, 마이그레이션으로 v18→v19·재시작 설정 보존을 확인합니다.
UI의 500 kB 청크 경고는 남아 있으며 빌드 실패는 아닙니다.

```sh
bash scripts/maven.sh test -Dtest=PollingIntegrationTest,SourcePollingTest,MigrationTest,SourceIntegrationTest
cd frontend && npm test -- --run src/SourcePollingSettings.test.tsx src/SourcePanel.test.tsx
# 전체 빌드 후 실제 60초 예약과 재시작 확인. 가상 파일, 임시 DB, 외부 AI 없음.
node scripts/polling-smoke.mjs
```

미리보기 `v0.1.0-preview.1` DMG에는 이 기능이 없습니다. 해당 설치 파일은 스키마 v18 기준입니다.
v19 DB를 이전 v18 앱으로 열 수 없습니다. 업데이트 검증은 복사한 백업/임시 DB로 진행하세요.
