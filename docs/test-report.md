# Phase 1 검증 결과

검증일: 2026-10-21 (Asia/Seoul)
환경: macOS Intel, Amazon Corretto 21.0.10, Node 24.2.0, npm 11.3.0,
Maven 3.9.11, Spring Boot 4.1.1.

## 실행 결과

| 검증 | 실제 실행 | 결과 |
|---|---|---|
| 백엔드 통합 테스트 | `bash scripts/maven.sh clean verify` | 11개 통과, 실패/오류/skip 0 |
| React/API 클라이언트 테스트 | `cd frontend && npm test` | 7개 통과 |
| TypeScript + Vite 번들 | `cd frontend && npm run build` | 성공 |
| 실행 JAR 생성 | Maven verify의 Boot repackage | 성공, 약 36 MiB |
| 실제 HTTP/재시작 검증 | `node scripts/smoke.mjs` | 통과 |
| 개발 서버 | `npm run dev` | 127.0.0.1:5173 기동 성공 |
| 개발 API 프록시 | `GET http://127.0.0.1:5173/api/session` | 백엔드 세션 반환 확인 |
| Bash/Node 스크립트 구문 | `bash -n`, `node --check` | 성공 |
| 실제 브라우저 | ego-browser, 패키지 UI | Workspace 생성 → 폴더 생성/진입 → 가상 파일 업로드 → 한글 원문 미리보기 확인 |

자동 테스트에서 Workspace 전환·삭제·마지막 선택 복원, 폴더 cascade,
다른 Workspace 접근 차단, 중복 이름 409, 경로·영역·부모 검증, 10 MiB 제한,
바이너리/큰 파일 미리보기 제한, UTF-8 원문 보존, 외부 Origin/Host 방어를 검증했습니다.

UI 테스트에서 생성 후 화면 전환, 원문 HTML 비실행, 삭제 취소 시 API 미호출,
실패 메시지 유지, 폴더 이동과 지식 영역 전환 시 경로 초기화,
multipart 헤더 및 204/오류 응답 처리를 검증했습니다.

실제 JAR 테스트는 별도 임시 DB를 사용합니다. UI 포함 여부, 업로드/다운로드 바이트 동일성,
한글 미리보기, 프로세스 종료·재기동 후 내용과 활성 Workspace 보존,
지식 영역 독립, Workspace 삭제 및 재기동 후 빈 상태를 확인했습니다.

## 성능 측정

원시 결과는 [performance.json](performance.json)에 있습니다.
100개의 가상 UTF-8 파일(각 약 2.5 KB), 폴더 1개, 하위 fixture 1개를 사용했습니다.

| 항목 | 결과 |
|---|---|
| JAR 기동 후 첫 정상 HTTP 응답 | 13,610 ms |
| 동일 DB 재기동 후 첫 정상 응답 | 13,875 ms |
| 순차 파일 업로드 100회 p50 / p95 | 15.12 / 28.56 ms |
| 루트 101개 항목 조회, 5회 warm-up 후 50회 p50 / p95 | 12.07 / 16.78 ms |
| 프런트엔드 JS / gzip | 266.70 / 84.54 kB |
| 프런트엔드 CSS / gzip | 15.94 / 4.65 kB |

로컬 단일 프로세스, warm filesystem cache, 순차 HTTP 요청 결과입니다.
기동 측정 당시 다른 검증 프로세스도 실행 중이었습니다.
동시 수집, 큰 저장소, 검색 인덱싱, LLM 지연에 대한 수치가 아닙니다.

## 발견 및 해결

1. Spring 7의 `ProblemDetail.forStatusAndDetail`이 `HttpStatusCode`를 요구하여 컴파일 실패:
   명시적 상태 코드 타입으로 수정하고 재빌드했습니다.
2. Vitest hook에서 mock 함수를 반환하여 cleanup으로 잘못 실행:
   반환 없는 hook으로 수정했습니다. Testing Library의 지원하지 않는 `exact` 옵션도 제거했습니다.
3. SQLite unique 오류가 Spring 기본 번역기에서 분류되지 않아 409 대신 예외 발생:
   저장 어댑터에 SQLite unique 전용 예외 번역을 추가하고 중복 생성/비덮어쓰기 테스트를 통과했습니다.
4. 샌드박스 DNS/localhost 제한으로 의존성 다운로드·실제 서버 테스트 실패:
   승인된 네트워크/로컬 서버 실행으로 재검증했습니다.

## 남은 검증 범위

이번 Phase 1 범위의 자동 테스트 실패는 남아 있지 않습니다.
Mockito 동적 agent 로딩 경고가 있으며 Java 21에서는 테스트가 정상 실행됩니다.
향후 JDK 업그레이드 시 명시적 agent 설정을 검토해야 합니다.

Windows, Apple Silicon, 설치형 앱, 대규모/동시 작업, 전원 장애 복구, 접근성 감사,
외부 Collector·검색·AI·폴링은 아직 검증하지 않았습니다.
기능별 후속 완료 기준은 `phase-plan.md`에 정리했습니다.
