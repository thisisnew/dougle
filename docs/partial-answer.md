# 생성 중 임시 출력 — Claude부터 지원

> 이후 [Codex 선택적 실행기](codex-streaming.md)를 연결했습니다. 아래의 Codex 완료 답변 설명은 기본 설정 기준이며 전용 옵션 사용 시 임시 출력도 지원합니다.

2026-10-22 · SQLite v16 유지. **실제 서비스 호환성·답변 품질은 아직 미검증이며 가상 CLI로 검증합니다.**

## 어떻게 보이나요?

- Claude로 승인 후 답변을 시작하면 CLI가 보내는 텍스트 조각을 **미검증 임시 출력** 영역에 표시합니다.
- 중간 글은 최종 답변이 아닙니다. 인용·사실성을 검증하지 않았으며 최종 결과와 다를 수 있습니다.
- 완료하면 임시 글을 지우고 기존 인용/근거 검사를 통과한 최종 답변만 표시·저장합니다.
- 실패·중단·연결 오류·화면 닫기 때 임시 글은 지웁니다. 연결 복구 시 실행 중인 작업의 현재 임시 글을 다시 받을 수 있습니다.
- **Codex는 여전히 완료 답변만** 표시합니다. 완성된 답변을 쪼개서 스트리밍으로 가장하지 않습니다.
- Claude도 텍스트 이벤트 없이 구조화 결과만 보내면 임시 영역이 나타나지 않습니다. 지원 플래그가 없는 버전은 실패하며 자동 재전송하지 않습니다.

한 번 보낸 자료나 이미 화면에 보인 임시 글을 외부 서비스/사용자 기억에서 회수할 수는 없습니다.
창 닫기는 관측만 종료합니다. 생성 중단은 기존 **답변 중단** 버튼을 사용하세요.

## 실제 설계와 코드 대조

```text
명시적 승인/전송
  → AnswerJobService
  → StreamingAnswerModel → CliAnswerModel → CliCuratorModel
      Claude stdout JSONL → ClaudeTextStream → 텍스트 누적
          → 실행 중 작업 메모리 → SSE partial → 미검증 임시 영역
      성공 structured_output → 기존 AnswerOutput 검사 → DB 최종 답변
```

| 코드 | 실제 역할 |
|---|---|
| `StreamingAnswerModel` | 임시 텍스트 콜백을 제공하는 선택적 생성 포트 |
| `CliCuratorModel.generateWithText` | Claude 답변만 stream-json 플래그 사용. 지식 초안의 기존 생성 경로는 그대로 |
| `ClaudeTextStream` | 주 대화의 시작된 text 블록에서 text_delta만 누적. thinking/tool 인자/subagent 이벤트는 표시하지 않음 |
| `AnswerJobService.partial` | Workspace/작업 범위 확인 후 실행 중 메모리 스냅샷만 반환 |
| `AnswerJobEventsController` | 근거·작업 이벤트 뒤 임시 스냅샷 전달, 최종 상태는 기존 job 이벤트 |
| `observeAnswerJob`, `AnswerJobsPanel` | 순서·범위·길이 검사 후 텍스트로 교체 표시. 최종/오류/중단/재연결 시 임시 표시 제거 |

SSE는 기존 500ms 관측 주기에 **현재까지 누적된 문자열**을 보냅니다. 토큰 하나마다 이벤트 하나를 보장하지 않습니다.
SSE `partial`은 덧붙이는 delta가 아니라 교체용 스냅샷입니다. 재연결해도 AI를 다시 호출하지 않습니다.
메모리 임시 글은 20,000 UTF-16자까지이며 DB·지식 문서·작업 이력에 저장하지 않습니다.
실행기 한 개의 버퍼만 유지하며 최종 성공/실패/중단/서버 종료 때 비웁니다. 재시작 복구 대상이 아닙니다.
취소/승인 철회와 관측 사이에는 기존 최대 약 500ms 감지 지연 및 전송 경쟁이 있을 수 있습니다.

## 추가 API 이벤트

기존 `GET /api/workspaces/{workspaceId}/answer-jobs/{id}/events`에 다음 이벤트를 추가했습니다.

```json
{"workspaceId":"…","jobId":"…","bundleId":"…","text":"현재까지의 임시 글","unverified":true}
```

이벤트 이름은 `partial`입니다. `evidence → job → partial … → 최종 job → done` 순서입니다.
빠르게 끝나거나 텍스트가 없으면 partial이 생략됩니다. 빈 text는 임시 글 제거입니다.
`answer`는 여전히 최종 SUCCEEDED 작업의 필드입니다. 중간 글을 answer에 합치면 안 됩니다.
기존 소비자는 partial을 무시해도 최종 답변을 받을 수 있습니다. 승인 digest V2·DB 스키마·요청 본문은 바뀌지 않았습니다.

## 안전장치

- 기존 도구/MCP/후크 차단, 격리 임시 디렉터리, 120초 제한, 프로세스/자식 종료 경로를 유지합니다.
- stdout 총 262,144바이트·JSONL 한 줄 65,536바이트·최종 JSON 65,536자 제한. 과다 출력/잘못된 JSON/실패·누락 결과는 실패 처리합니다.
- 중간 글이 왔더라도 종료 코드 0과 성공 structured_output이 없으면 최종 답변을 저장하지 않습니다.
- 마지막 결과는 중간 글에서 조립하지 않고 독립적으로 기존 인용/근거 검사를 거칩니다. 이는 자동 사실성 판정이 아닙니다.
- raw stderr·메타데이터·비텍스트 스트림 이벤트는 UI에 전달하지 않습니다. 임시 글도 HTML/이미지로 실행하지 않습니다.

## 프로토콜 확인 근거와 한계

[Claude 공식 프로그램 실행 문서](https://code.claude.com/docs/en/headless)는 stream-json, verbose, include-partial-messages 및 text_delta 필터를 설명합니다.
로컬 `claude --help`(2.1.31)에서 해당 옵션을 확인했습니다. 로그인/실제 추론 호출은 하지 않았습니다.
[Codex 공식 비대화 실행 문서](https://learn.chatgpt.com/docs/non-interactive-mode)는 exec JSONL 작업 이벤트를 설명하고,
[App Server 문서](https://learn.chatgpt.com/docs/app-server)는 agentMessage delta와 초기화·turn 프로토콜을 설명합니다.
따라서 이번에는 Codex exec를 임의로 토큰 스트림이라 간주하지 않고 그대로 두었습니다. Codex App Server 어댑터는 다음 작업입니다.

남은 것: 실제 승인된 Claude 서비스 검증, Codex 토큰 스트림 어댑터, 다중 대화/예산,
장시간 단절/부하 검증, 선택적 자동 갱신, Mac 설치형. P4 전체 완료는 아닙니다.

## 이번 검증 결과

| 실행 | 결과 |
|---|---|
| `bash scripts/maven.sh clean verify` | 243개 중 242 통과, 실제 CLI opt-in 1 skip, JAR 빌드 성공 |
| `npm --prefix frontend test -- --maxWorkers=2` | 21개 파일, 139개 통과 |
| `npm --prefix frontend run build` | TypeScript/Vite 성공 |
| `node scripts/smoke.mjs` | 재시작 회귀 + 실제 fake Claude 프로세스→SSE partial→독립 최종 답변 통과 |
| ego-browser + 격리 임시 DB + fake Claude | 최종 답변 전 미검증 영역 표시, 최종 답변으로 교체, 별도 작업 중단 후 임시 글·답변 없음 확인 |

새 백엔드 테스트 11개: 텍스트 누적/비텍스트 제외/오류 결과/용량 제한, 프로세스 종료 전 콜백,
잘못된 스트림의 신속한 종료, 임시 글의 범위·비저장·최종 교체·중단·실패·삭제, SSE 순서.
새 UI 테스트 5개: 임시 스냅샷 교체·재연결/완료 폐기, 잘못된 범위/검증 표시/길이 거부, HTML 비실행·실패 시 제거.
작은 가상 자료 검색 성능은 `performance-phase3-foundation.json`에 재기록했습니다. SSE 부하/실제 AI 성능 측정은 아닙니다.
