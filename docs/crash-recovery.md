# 작업 도중 앱이 강제 종료되면?

**이미 저장한 자료는 유지하고, 중단된 AI 요청은 자동으로 다시 보내지 않습니다.** 다시 시작하려면 내용을 확인하고 새 요청을 승인하세요. 실제 외부 서비스에 이미 전달된 요청이나 사용 요금까지 취소된다는 뜻은 아닙니다.

## 이번에 검사한 것

0.1.5의 실제 JAR를 임시 폴더에서 실행하고, 가상 AI 초안·답변 요청이 모두 실행 중임을 확인한 뒤 테스트 프로세스 그룹에 `SIGKILL`을 보냈습니다. 정상 종료 처리 없이 같은 데이터로 다시 시작합니다. 사용자 앱이나 실제 회사 자료는 사용하지 않습니다.

| 확인 항목 | 기대 결과 |
|---|---|
| 실행 중 초안·답변 | 재시작 후 `INTERRUPTED`, 결과 없음 |
| 원본·지식 | 업로드 바이트, 사람 편집 v2, 이전 v1, 게시 v1 그대로 |
| 검색·근거 | 검색 결과와 저장한 근거 묶음 유지 |
| 답변 SSE 재연결 | 근거 → 중단된 작업 → 종료 순서, 임시 답변 없음 |
| 같은 작업 ID 재전송 | 기존 중단 이력 반환, AI 실행 횟수 증가 없음 |
| 이미 소비한 답변 승인 재사용 | 새 작업 ID라도 409 거부 |
| 한 번 더 재시작 | 중단 상태·시각 유지, 자동 재실행 없음 |
| 새로 승인한 요청 | 가상 답변 성공, 별도 AI 초안 생성; 사람 편집·게시본 유지 |

## 코드와 모델

- `DatabaseConfiguration`: 시작 시 `QUEUED/RUNNING/CANCELLING` 작업만 `INTERRUPTED`로 바꿉니다. 이미 종료된 상태는 바꾸지 않습니다.
- `GenerationJobService` / `AnswerJobService`: 같은 ID의 동일 요청은 기존 이력을 반환합니다. 중단 작업을 다시 실행하지 않습니다.
- `AnswerJobService` / `JdbcAnswerJobStore`: 이미 작업에 사용된 승인으로 새 답변 작업을 만들지 않습니다.
- `AnswerJobEventsController`: 저장한 근거를 먼저 보내고 종료 상태를 관측합니다. SSE 연결은 새 AI 실행이 아닙니다.
- `scripts/fixtures/fake-codex-recovery.sh`: 입력을 받은 횟수를 기록하고 대기하는 가짜 CLI입니다. 실제 인증·외부 AI 통신은 없습니다.

## 실행 방법

개발용 Node와 Java 21, 빌드된 JAR가 필요합니다. Mac/Linux의 POSIX 프로세스 그룹을 사용합니다.

```bash
node scripts/crash-recovery-smoke.mjs
```

동봉 Java와 JAR로 검사하려면 해당 CPU에서 실행하세요.

```bash
FORGE_RECOVERY_RESOURCES="$PWD/desktop/src-tauri/target/release/bundle/macos/Knowledge Forge.app/Contents/Resources/resources" node scripts/crash-recovery-smoke.mjs
```

검사 도구가 직접 만든 임시 폴더·프로세스만 정리합니다. 데이터 경로를 사용자에게서 받지 않으며, JVM 임시 파일도 검사 폴더 안에 둡니다.

## 결과와 범위

개발 Java 및 Intel 앱에 동봉한 Java/JAR로 검사했습니다. 실행 결과는 [기계 판독 보고서](crash-recovery-report.json)에 기록합니다. 검사한 JAR SHA-256은 `5fbaadb8cdd7f4fd7bc35815cbcd4f78c72624a560c22e21137d7533146897c3`으로 0.1.5와 같습니다.

이는 **실제 JAR/API의 강제 프로세스 종료 복구 검사**입니다. 물리적 정전·디스크 손상·트랜잭션의 모든 중간 시점, 네이티브 앱 감독자만 종료되는 상황, ARM 실기기, 실제 AI의 원격 중단·과금까지 검증하지 않습니다. 브라우저 UI 전체 E2E를 다시 실행한 것도 아닙니다.

이번 변경은 검사 도구와 문서뿐입니다. 앱·DB·UI가 바뀌지 않아 0.1.5 설치 파일과 업데이트 피드를 그대로 유지합니다.
