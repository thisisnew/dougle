# Codex 답변 스트리밍 사용법과 구현

**0.1.7 Mac 앱은 아래 터미널 준비 대신 AI 연결 화면의 전용 로그인을 사용합니다.** 초안도 같은 인증을 사용하며 기존 일반 로그인을 복사하지 않습니다. [새 사용법·구현](codex-managed-login.md), [현재 배포 검증](release-0.1.7-report.md). 아래는 최초 opt-in 개발 경로의 기록입니다.

2026-10-22 · SQLite v16 유지. **전용 프로필을 지정하면 Codex 중간 출력을 표시합니다.**
가상 CLI로 검증하는 기능이며 실제 계정의 답변 생성 성공은 별도 검증이 필요합니다.

## 무엇이 달라졌나요?

- 기본 설정은 그대로입니다. Codex는 완료 답변만 표시하고 Claude는 기존 스트리밍 경로를 씁니다.
- 새 옵션을 켜면 Codex도 **미검증 임시 출력 → 검사된 최종 답변** 순서로 표시합니다.
- 중간 글은 DB나 지식 문서에 저장하지 않습니다. 실패·중단 시 폐기합니다.
- 전송 승인은 여전히 요청마다 필요합니다. 실패해도 다른 방식으로 자동 재전송하지 않습니다.
- 지식 초안 생성은 기존 exec 경로를 유지합니다. 이번 변경은 **답변 작업**에만 적용됩니다.

## 켜는 방법

Mac/Linux 개발 환경용 옵션입니다. 기존 `~/.codex`나 현재 `CODEX_HOME` 폴더를 지정하지 마세요.
새 폴더를 만들고 별도로 로그인합니다. 아래 명령은 사용자가 직접 실행하는 준비 절차이며 앱이 대신 로그인하지 않습니다.

```bash
mkdir -m 700 "$HOME/.knowledge-forge-codex"
CODEX_HOME="$HOME/.knowledge-forge-codex" codex -c 'cli_auth_credentials_store="file"' login
chmod 600 "$HOME/.knowledge-forge-codex/auth.json"
bash scripts/run.sh --forge.ai.codex-stream-home="$HOME/.knowledge-forge-codex"
```

같은 폴더가 이미 있으면 새로 덮어쓰지 말고 용도를 확인하세요. 별도 CLI 설정·MCP·사용자 skill을 이 폴더에 넣지 않습니다.
옵션을 빼고 앱을 재시작하면 기존 완료 답변 경로로 돌아갑니다. 전용 로그인 폴더는 자동 삭제하지 않습니다.
CLI가 토큰 갱신·캐시·메타데이터를 이 폴더에 쓸 수 있습니다. 앱은 기존 인증 파일을 복사하거나 내용을 읽어 로그에 남기지 않습니다.
기본 Codex/지식 생성과 전용 스트리밍은 로그인 프로필이 다를 수 있으므로 사용할 계정은 직접 확인하세요.

## 실제 작동 원리

```text
승인한 답변 작업
  → CliCuratorModel.generateWithText
      옵션 없음: 기존 codex exec
      옵션 있음: CodexAppServer
        → 전용 폴더 검사·잠금, 요청 전용 임시 작업 폴더
        → initialize → config/read → thread/start의 안전 설정 검사
        → turn/start로 승인된 요청문 한 번 전송
        → CodexTextStream → 작업 메모리 → 기존 SSE partial → 임시 영역
        → 완료 원문 → 기존 인용/현재 근거 검사 → 최종 답변 저장
  → 완료·실패·중단 시 프로세스 정리
```

| 코드 | 대조한 동작 |
|---|---|
| `CodexAppServer` | POSIX 전용 폴더 권한/일반 프로필 재사용 금지, 잠금, 환경 변수 제한, stdio 프로세스·한도·정리 |
| `CodexAppServerSession` | 응답 ID/설정/실제 thread 정책 검사. 응답 전 알림 최대 128개를 임시 보관 |
| `CodexTextStream` | thread/turn/item 범위·순서 검사, 중간/최종 분리. 이전 검증기 재사용 |
| `CliCuratorModel` | 새 옵션은 Codex 답변에만 적용. 지식 생성·Claude 경로는 변경하지 않음 |
| `AnswerJobService`, SSE/UI | 기존 건별 승인·취소·인용 검사·메모리 partial 흐름 재사용 |

## 보호 범위와 한계

- `HOME`, `XDG_CONFIG_HOME`, 작업 폴더와 임시 경로를 요청별로 분리합니다. API 키·프록시 등 임의 환경 변수는 넘기지 않습니다.
- read-only, 승인 never, 웹 검색 OFF, shell/image/skill 검색/hooks/plugins/MCP 관련 기능 OFF를 강제합니다. 실제 조회 결과도 대조합니다.
- 활성 설정 layer에 앱의 명령줄 설정 이외의 내용이 있으면 요청문 전송 전에 중단합니다. 회사 관리 정책을 우회하거나 수정하지 않습니다.
- thread가 ephemeral/read-only/네트워크 차단인지, 지침 파일이 없는지, 명시한 모델이 일치하는지 확인합니다.
- 서버 승인 요청에는 승인 응답을 보내지 않고 중단합니다. 예상하지 않은 도구 실행 알림도 실패 처리합니다.
- 폴더에는 인증·허용된 CLI 메타데이터/SQLite 파일과 `tmp`, 번들 `skills/.system`만 허용합니다. 사용자 skill/config나 루트 심볼릭 링크는 거절합니다. 새 CLI 버전이 다른 파일을 만들면 다음 실행이 차단될 수 있습니다. **내용을 무작정 지우거나 제한을 풀지 마세요.**
- 번들 system skill과 설치된 CLI 자체는 신뢰 경계 안입니다. 같은 OS 계정이 프로필·바이너리를 악의적으로 바꾸는 상황까지 방어하는 OS 격리 기능은 아닙니다.
- 120초, stdout 1 MiB/한 줄 256 KiB, stderr 256 KiB 제한. 중단 시 관측한 자식과 부모를 종료하고 임시 작업 폴더를 정리합니다. 강제 앱 종료·순간적으로 분리된 자식까지 감독하는 OS 서비스는 후속입니다.
- 실제 CLI는 인증·설정 조회 중에도 자체 네트워크 연결/메타데이터 처리를 할 수 있습니다. read-only sandbox는 모델 서비스 연결 자체를 차단하는 설정이 아닙니다.
- 완성된 JSON의 인용 번호 검사는 사실성 검증이 아닙니다. 다중 대화와 실제 모델 품질 검증은 남았습니다.

## 검증 기록

### 2026-10-26 후속 소스: 내용 없는 스트림 진단

실제 E05 variant0의 세 번째 답변은 120초 뒤 `WAIT_RESPONSE`, stdout 24,623바이트, stderr 0바이트로 실패했다. **원인은 미확정**이다. `WAIT_RESPONSE`는 `turn/start` 응답 접수 이후 상태이며, 바이트 수만으로 최종 완료 알림 수신·무시, 제공자 지연, 로컬 콜백 정체를 구별할 수 없다. 아래 후속 진단을 과거 실패에 소급 적용하거나 해결 증거로 취급하지 않는다.

- 기존 transport/phase/바이트 진단 뒤에 선택적 `appStream=AppStream[...]`을 추가한다. 필드 순서는 `phase`, `turnAccepted`, `messages`, `deltas`, `completedMessages`, `matchedCompletions`, `ignoredBindings`, `otherNotifications`, `protocolInvalid`, `pendingLineBytes`, `callbackActive`로 고정한다.
- `phase`는 `NONE`, `TURN_ACCEPTED`, `MESSAGE_STARTED`, `TEXT_DELTA`, `MESSAGE_COMPLETED`, `TURN_COMPLETION_RECEIVED`, `COMPLETED`, `PROTOCOL_INVALID` 중 하나다. 그 외 필드는 boolean 또는 정수이며 이벤트 카운터는 1,048,576에서 포화하고 대기 중인 줄은 최대 262,144바이트로 표시한다.
- 메시지/델타/메시지 완료는 일치한 범위의 agent message 처리 횟수다. `matchedCompletions`는 thread/turn이 일치한 `turn/completed` 수신 횟수이지 성공 횟수가 아니다. 완료 상태·최종 후보·순서 검사는 그대로 유지한다. `ignoredBindings`는 누락 또는 불일치한 범위로 무시한 알림 수다.
- `pendingLineBytes`는 아직 줄바꿈으로 닫히지 않은 입력을, `callbackActive`는 로컬 텍스트 전달 콜백 실행 중 여부를 구별한다. `protocolInvalid`는 세션/스트림 처리 실패 표식이며 제공자 귀책을 확정하지 않는다. 단일 진단만으로 정체 원인을 단정하지 않는다.
- 원문 알림·prompt·thread/turn/item ID·추론·인증 키를 새 진단에 저장하거나 출력하지 않는다. 기존 6인자 typed diagnostic 생성자와 그 요약 문자열은 호환 유지한다. 실패는 실패로 남고 120초 제한·자동 재시도 금지·엄격한 파서 정책은 바꾸지 않았다.
- 관련 오프라인 검사 **58개 통과, 실패/건너뜀 0개**: `CodexAppServerTest` 19, `CodexAppServerSessionTest` 8, `CodexTextStreamTest` 14, `ProcessDiagnosticsTest` 6, `CliPlanTransportTest` 8, `ArchitectureContractTest` 3. 새 가상 CLI 시간 초과 사례는 1.5초 제한으로 범위 불일치 완료, 줄바꿈 누락, 잘못된 완료, 콜백 정체와 프로세스/임시 폴더 정리를 검사한다. 합성 프로필과 가상 CLI만 사용했으며 실제 인증·네트워크·AI 실행이나 E05 원인 재현 검증이 아니다. 로그: `/tmp/dougle-e05-bounded-diagnostics.log`.

완료 생명주기 참고: [공식 Codex App Server 문서](https://learn.chatgpt.com/docs/app-server). 이 후속 검사는 새 JAR·설치본 검증을 뜻하지 않으며, 아래 수치는 초기 구현 당시 기록이다. 서로 중복 합산하지 않는다.

### 초기 구현 검증

- 로컬 `codex-cli 0.163.0`에서 **인증 없는 새 임시 프로필로 initialize/config/read만** 실행했습니다. 답변 생성 요청은 보내지 않았습니다.
- 이 확인에서 strict config가 거부한 오래된 `tools.view_image` 설정을 `--disable view_image`로 바꿨습니다. 생성되는 CLI 캐시 이름과 실제 설정 응답도 대조했습니다.
- 새 프로세스 테스트 14개: opt-in 연결, 임시/최종 출력, 조기 알림, 위험 설정·도구·승인 요청 거절, 취소/자식 정리, 시간/출력 한도, 인증 파일 보존, 재실행.
- 세션 상태 테스트 7개: 전송 전 검사, 누락된 기능 증명/MCP/지침/모델 차단, 엄격한 정책 타입, 조기 알림 한도, 서버 요청 차단.
- 최초 컴파일에서 진단 어댑터가 쓰던 3인자 생성자 호환성 누락을 발견해 복원했습니다. 이후 좁은 범위 테스트 39개가 통과했습니다.
- 최초 전체 UI 실행에서 기존 `App.test.tsx`의 원본 이동 테스트가 5초 제한을 한 번 넘겼습니다. 해당 코드/제한 변경 없이 대상 15개와 전체 139개 재실행이 통과했습니다. 시간 초과 재현 조건은 확정하지 못했습니다.
- 최종 회귀: 백엔드 **275개 통과 + 실제 CLI opt-in 1개 skip**, UI **139개 통과**, TypeScript/Vite와 Maven `clean verify` 빌드 성공.
- 실제 JAR smoke: 기존 exec/Claude 경로, 전용 Codex opt-in의 근거 우선 SSE·임시 글·검사된 최종 답변·명시적 중단·재시작 통과. 전부 가상 CLI이며 실제 서비스 생성 검증이 아닙니다.
- 브라우저에서 가상 Codex의 임시 글이 먼저 나타나고 최종 `[R1]` 답변으로 바뀌는 것을 확인했습니다. 새 승인으로 실행한 작업을 중간 출력 중 중단하면 임시 글과 최종 답변이 모두 사라지고 `중단됨`으로 표시됐습니다. 짧은 가상 응답 때문에 첫 중단 클릭은 완료와 경합했고, 새 승인에서 버튼 DOM 클릭으로 중단 경로를 검증했습니다. 같은 승인 재전송은 기존 작업을 반환했습니다.
- 소규모 합성 자료 검색 p95 약 **26.19ms**. [측정 원본](performance-phase3-foundation.json)은 순차 HTTP 측정이며 AI 성능이나 동시 사용자 부하 성능을 뜻하지 않습니다.

공식 프로토콜: [Codex App Server](https://learn.chatgpt.com/docs/app-server). 검증기는 설치 버전에서 생성한 스키마와 실제 설정 조회 응답을 함께 대조했습니다. 구버전에서 필수 안전 필드가 없으면 실패합니다.
