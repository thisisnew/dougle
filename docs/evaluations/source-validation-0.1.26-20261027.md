# 0.1.26 후보 소스 검증

고정 JAR SHA-256: `f88d6f01b115c5951a98c35dd6723b447c4bce1da03843666a8ad5f64f368093`.

## 변경

- 전체 자료 export가 아닌 제한형 팀 설정/ZIP 이전 화면 제거. 개별 원본 다운로드·기존 자료·호환 API는 보존한다.
- 텍스트 질문에서 계획, 추가 로컬 검색, 당시 검색 결과 스냅샷 검증, 답변을 연결한다. 재열기는 상태 GET만 하며 AI를 자동 재실행하지 않는다.
- 운영 조회 제안은 업무 범위 확인과 읽기 동의를 거친다. MySQL/Datadog 결과는 별도 확인 후 같은 대화에 첨부하며 외부 쓰기를 허용하지 않는다.
- 범위 철회·연결 변경·시간 제한을 조회 checkpoint에 연결한다. 저장 응답 유실 시 기존 영수증을 GET으로 확인하며 저장/조회/AI를 자동 반복하지 않는다.
- AI 스트림의 무시된 이벤트는 내용/식별자 대신 제한된 분류별 수만 진단한다. 과거 timeout 해결을 의미하지 않는다.

## 최종 실행

| 검사 | 결과 | 로그 |
|---|---|---|
| Java `test '-Dtest=!AnswerPartialIntegrationTest,!QuestionAttachmentIntegrationTest'` | 1,132 실행, 1,125 통과, opt-in 7 건너뜀, 실패/오류 0 | `/tmp/dougle-026-java-final.log` |
| UI `npm test -- --maxWorkers=2` | 63파일, 669 통과 | `/tmp/dougle-conversation-ui-contract-final.log` |
| UI `npm run build` | 통과, main JS 500 kB 초과 경고 | `/tmp/dougle-conversation-ui-contract-final-build.log` |
| Node `node --test scripts/*.test.mjs` | 153 통과 | `/tmp/dougle-026-node-final.log` |
| Maven `-DskipTests package` | 통과 | `/tmp/dougle-026-jar-build.log` |
| JAR/dist 파일 및 바이트 대조 | 7파일, 1,660,652 bytes 일치 | `scripts/verify-frontend-bundle.mjs` |

Java에서 제외한 두 클래스는 기존 fixture 문제이며 전체 테스트 무조건 통과로 보고하지 않는다. 중간 focused 검사 수와 합산하지 않는다. UI 최초 전체 실행의 과거 API fixture/비동기 검증 실패는 `/tmp/dougle-conversation-ui-full.log`에 보존했고 실제 응답 계약으로 수정 후 최종 전체를 다시 실행했다. 독립 검토에서 발견한 thread DTO 최상위 workspace 필드 불일치도 수정하고 실제 `{id,bundles,jobs}` 계약으로 재검증했다.

추가 `node scripts/smoke.mjs`는 실패했다(`/tmp/dougle-026-smoke.log`). 기존482행 fixture가 20자를 초과하는 Workspace 이름을 생성해 현재 API의 정상적인400 거부에서 중단됐다. 이름 제한을 완화하거나 이번 변경과 무관한 fixture를 수정하지 않았다. 이 범용 smoke 전체를 통과했다고 보고하지 않으며 패키지의 격리 native/WebKit 검사는 별도다.

## 범위

이 기록은 소스·합성 회귀 검사이며 실제 회사 MySQL/Datadog, ARM 실기기, 두 번째 Mac, 전체50개 인수 통과가 아니다. 실제 AI b1/b2 검사는 이전0.1.25 아티팩트의 별도 기록이다. 설치·패키지 검증은 별도 기록이 생성된 뒤에만 완료로 표시한다. 공개 릴리스와 업데이트 피드는 보류한다.
