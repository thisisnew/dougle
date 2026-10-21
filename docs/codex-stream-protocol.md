# Codex 스트림 검증기와 남은 연결 작업

2026-10-22. **검증기만 구현했습니다. 앱의 Codex 답변은 아직 완료 후 표시합니다.**
설정이나 로그인 파일을 변경하지 않았고 실제 AI를 호출하지 않았습니다.

## 쉽게 말하면

AI가 보내는 중간 메시지에서 답변 글만 골라내는 검사기를 만들었습니다.
다른 대화의 글, 도구 인자, 추론 이벤트를 답변으로 표시하지 않습니다.
중간 글과 최종 답변은 별개입니다. 정상 종료를 확인해야 최종 결과를 꺼낼 수 있습니다.
이 검사기를 실제 Codex 실행기에 연결하는 작업은 다음 단계입니다.

## 구현과 설계 구분

```text
현재 앱: 승인 → Codex exec → 완료 JSON → 인용 검사 → 답변 저장

이번에 검증한 독립 부품:
합성 App Server 알림 → CodexTextStream → 미검증 문자열 콜백
                                     → 정상 종료 후 완료 메시지 원문

다음 연결: 격리된 App Server 실행기 → 위 검증기 → 기존 작업 메모리/SSE
```

| 항목 | 코드 대조 결과 |
|---|---|
| `knowledge/adapter/CodexTextStream` | 구현. 생성자에 thread/turn ID를 고정하고 다른 범위의 알림을 제외 |
| `item/started → delta → item/completed → turn/completed` | 구현. 시작되지 않은 조각, 중복 ID, 겹친 메시지, 늦은 조각을 거절 |
| 중간 출력 | 메시지별 교체 스냅샷. 명시된 commentary, reasoning/tool 이벤트는 표시하지 않음 |
| 최종 출력 | 조각을 이어 만든 문자열이 아니라 완료 메시지의 원문. 성공한 turn 뒤에만 반환 |
| 오류·승인 요청 | 오류 후 재사용 불가. 서버 요청/응답을 알림으로 받으면 거절하며 승인 응답을 만들지 않음 |
| DB·HTTP·화면 | 변경 없음. 검증기를 사용하는 실제 앱 실행 경로는 아직 없음 |

`phase`가 없거나 null인 구버전 메시지는 종류를 알 수 없습니다. 임시 출력으로 취급하고 정상 turn 종료 후 마지막 완료 메시지를 후보로 반환합니다.
명시적인 `partial_answer`나 `commentary`만으로 최종 답변을 확정하지 않습니다.
최종 원문이 유효한 JSON/인용인지, 사실과 일치하는지는 이 부품의 검증 범위가 아닙니다.
연결 시 기존 `AnswerOutput` 검사와 근거 재검증을 반드시 거쳐야 합니다.

## 한도와 보안 경계

- 입력 한 줄 256 KiB, 누적 입력 1 MiB, agent 메시지 64개까지 받습니다.
- 임시 글은 20,000자, 완료 메시지는 65,536자까지입니다. 문자 한도는 Java UTF-16 기준입니다.
- 실패하면 후보를 버리고 일반 오류만 반환합니다. CLI 원문을 예외에 담지 않습니다.
- 이 부품은 이미 읽힌 문자열을 검사합니다. 실행기는 **읽기 전 바이트 한도·시간 제한·프로세스 정리**도 구현해야 합니다.
- 도구 알림을 표시하지 않는 것은 도구 실행 차단이 아닙니다. 실제 도구·MCP·네트워크 권한은 실행기에서 별도로 제한해야 합니다.

## 다음 작업의 인수 조건

1. 기존 사용자 설정/인증을 복사하거나 수정하지 않는 전용 실행 환경을 확정합니다. 전용 로그인 홈 방식은 **설계 후보**이며 사용할 설정 옵션은 아직 없습니다.
2. `initialize → initialized → thread/start → turn/start` 요청/응답을 연결합니다. 응답 ID, 실제 sandbox/승인 정책과 로드된 지침을 검사합니다.
3. 응답보다 먼저 오는 알림을 제한된 버퍼로 처리합니다. 검증기는 thread/turn ID가 확정된 뒤 알림만 받습니다. 서버 승인/도구 요청은 응답 승인 없이 중단합니다.
4. 취소·시간 초과·출력 초과·자식 프로세스 정리를 가상 프로세스로 검증합니다. 실패 시 자동으로 다른 CLI 경로에 재전송하지 않습니다.
5. 승인된 답변 작업에만 연결하고 실제 JAR/SSE에서 검증합니다. 지식 생성과 기존 Codex exec 경로는 유지합니다.
6. 실제 서비스 호환성 검증은 사용자가 별도로 승인한 가상 자료로만 합니다.

App Server에 exec의 `--ignore-user-config` 옵션이 있다고 가정하면 안 됩니다.
`config/read`의 요약 `config`만으로 MCP/features 전체를 검사했다고 주장할 수도 없습니다.
격리 정책을 입증하기 전에는 앱에 연결하지 않습니다.

## 검증

- `bash scripts/maven.sh -Dtest=CodexTextStreamTest test`: 합성 알림 테스트 **12개 통과**.
- 범위 격리, 정상/오류 종료, 구버전 phase, 중간/최종 분리, commentary 제외, 순서/중복/길이 한도, 서버 요청 거절, 콜백 실패를 검사합니다.
- `bash scripts/build.sh`: UI 139개 통과, TypeScript/Vite 빌드 성공, 백엔드 254개 통과 + 실제 CLI opt-in 1개 skip, 실행 JAR 빌드 성공.
- `node scripts/smoke.mjs`: 실제 JAR의 기존 저장/재시작/승인/가상 CLI/SSE 경로 통과. 새 Codex 검증기의 실행기 연결 검증은 아닙니다.
- 소규모 가상 자료 검색 p95 약 16.15ms. [원시 측정](performance-phase3-foundation.json)은 단일 로컬 프로세스 측정이며 실제 AI 속도나 스트림 부하 테스트가 아닙니다.
- 새 검증기는 합성 알림 테스트이며 기존 CLI/SSE 회귀 테스트와 별개입니다. UI 변경이 없어 새 브라우저 수동 검증은 하지 않았습니다.
- 실제 Codex App Server 프로세스나 외부 모델을 실행한 성공 검증은 아닙니다. 성능 향상도 주장하지 않습니다.

프로토콜 대조: 로컬 `codex-cli 0.163.0`의 `app-server generate-json-schema` 출력 중
`ItemStartedNotification`, `ItemCompletedNotification`, `AgentMessageDeltaNotification`, `TurnCompletedNotification`.
공식 계약은 [App Server 문서](https://learn.chatgpt.com/docs/app-server)를 참고합니다.
생성한 전체 스키마나 사용자 프로필은 저장소에 복사하지 않습니다.
