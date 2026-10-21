# 답변 작업 SSE — 근거를 먼저 보내는 관측 API

2026-10-22 · SQLite v16 유지. **API 구현이며 화면은 아직 기존 조회 방식입니다.**

## 쉽게 설명하면

이미 시작한 답변 작업의 소식을 연결 하나로 받습니다.
**당시 근거 → 작업 상태 → 검사를 통과한 완료 답변 → 연결 종료** 순서입니다.
AI가 쓰는 글자를 실시간으로 보내는 기능은 아닙니다. 완성된 답변을 잘게 나눠 스트리밍처럼 보이게 하지 않습니다.

연결하거나 다시 연결해도 AI를 새로 호출하지 않습니다. 전송은 기존의 **승인 후 답변 시작**만 가능합니다.
연결을 닫는 것은 관측 종료입니다. AI 작업 중단은 별도 **답변 중단** API/버튼을 사용하세요.

## 모델링과 코드 대조

```text
승인 + 명시적 POST → AnswerJobService → CLI → 인용 검사 → AnswerJob 저장
                                                          ↑ 읽기만
GET events → AnswerJobEventsController → 근거 → job 상태 → done
```

- `AnswerJobEventsController`: Workspace 범위의 작업/묶음을 먼저 확인하고 SSE를 엽니다.
- `EvidenceBundleService.get`: 저장 당시의 불변 근거입니다. 최신성 검사 결과가 아닙니다.
- `AnswerJobService.get`: 500ms마다 저장 상태를 조회합니다. DB 연결/트랜잭션을 대기 중 유지하지 않습니다.
- 새 테이블·승인·LLM 호출은 없습니다. 답변 검사는 기존 `AnswerOutput` 경로를 그대로 사용합니다.
- 먼저 보내는 것은 **연결 안에서의 이벤트 순서**입니다. AI 실행 전에 사용자가 근거를 수신했다는 보장은 아닙니다.

## API 계약

`GET /api/workspaces/{workspaceId}/answer-jobs/{id}/events`

성공: `200 text/event-stream`, `Cache-Control: no-store`. JSON은 각 이벤트의 `data`입니다.

| 이벤트 | 데이터 | 처리 |
|---|---|---|
| `evidence` | 기존 EvidenceBundle 전체 | 연결당 첫 이벤트, 인용·출처를 표시 |
| `job` | 기존 AnswerJob 전체 | 처음 및 관측된 변경 시 상태 교체, 성공이면 answer 포함 |
| `done` | `{ "reason": "terminal" }` | 완료/실패/취소/중단 기록 전달 후 연결 종료 |
| `done` | `{ "reason": "reconnect" }` | 약 55초 후 관측 연결 종료, 필요하면 다시 연결 |
| `unavailable` | `{ "message": "작업을 조회할 수 없습니다. 다시 확인하세요." }` | 관측 중 삭제 등, 연결 종료 후 목록을 다시 확인 |

변경이 없어도 5초마다 SSE 주석 heartbeat를 보냅니다. `delta`는 없습니다.
500ms 사이의 중간 상태는 생략될 수 있습니다. 영속 이벤트 로그/감사 기록이 아닙니다.
재연결은 **근거와 현재 상태를 다시 보내는 스냅샷 방식**이며 `Last-Event-ID` 재생은 지원하지 않습니다.
클라이언트는 덧붙이지 말고 작업 ID로 상태를 교체해야 합니다.

연결 전 잘못된 Workspace/작업/삭제된 묶음은 404, 외부 Origin/Host는 403입니다.
앱 전체 동시 관측 한도는 16개(초과 429), 종료 중은 503입니다.
가상 스레드당 관측 하나, 60초 async timeout, 연결 오류/timeout/서버 종료 시 관측 스레드를 중단합니다.
네트워크 단절 감지는 즉시 보장되지 않습니다. 다음 쓰기/heartbeat 또는 timeout에서 정리됩니다.
느린 클라이언트와 OS 소켓 종료의 모든 조합을 검증한 것은 아닙니다.

## 클라이언트 구현 시 주의

- `EventSource`의 자동 재연결에만 맡기지 마세요. terminal/unavailable에서는 `close()`합니다.
- reconnect 사유이면 기존 연결을 닫고 제한된 재시도 정책으로 연결합니다. 새 작업 POST는 보내지 않습니다.
- Workspace 전환/언마운트에서는 관측만 닫습니다. 작업 취소를 원하면 별도 POST cancel을 보냅니다.
- answer/근거는 텍스트로 렌더링하며 HTML·외부 이미지 자동 실행을 금지합니다.

## 검증과 남은 일

실제 임시 HTTP 서버 + 가상 서비스 데이터 테스트 6개:
근거 우선/완료/스냅샷 재연결, 실행 상태 변경/취소 결과, 도중 삭제, Workspace/작업 404,
외부 Origin 403, 동시 16개 한도/완료 후 용량 회수. 모든 테스트에서 AI 호출·작업 제출/취소 미호출을 확인합니다.
JAR smoke에는 실제 저장 묶음/완료 작업의 SSE 일치 및 재시작 후 재조회 검증을 추가했습니다.
smoke 모델은 fake CLI이며 실제 외부 AI 검증이 아닙니다.

남음: React SSE 연결·재연결 UI, CLI 실제 토큰 스트림, 부분 답변의 미검증 표시,
다중 대화, 장시간/느린 클라이언트 부하·연결 해제 스트레스 테스트. P4 전체 완료는 아닙니다.

### 이번 전체 실행 결과

| 실행 | 결과 |
|---|---|
| `bash scripts/maven.sh clean verify` | 232개 중 231 통과, 실제 CLI opt-in 1 skip, JAR 빌드 성공 |
| `cd frontend && npm test -- --maxWorkers=2` | 20개 파일, 118 테스트 통과 |
| `cd frontend && npm run build` | TypeScript/Vite 성공 |
| `node scripts/smoke.mjs` | 실제 JAR·SQLite 저장 데이터의 SSE 순서/내용과 재시작 후 재조회 통과 |

UI 변경은 없으며 이번에는 브라우저 수동 QA를 추가 실행하지 않았습니다.
작은 가상 자료 검색의 최신 측정값은 `performance-phase3-foundation.json`에 기록했습니다.
이는 SSE 지연/동시 부하나 실제 AI 성능 측정이 아닙니다.
