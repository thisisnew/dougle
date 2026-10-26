# E35 읽기 전용 dispatch 감사 — 승격 전 공백

2026-10-26 소스·기존 검사 산출물만 검토했다. 새 빌드/실행/외부 호출 없이 작성했으며 중앙 E35 판정은 변경하지 않았다. 부모의 `.23` 패키징 및 `fc3fab…` JAR와 아래 과거 `e0e43e10…` 실행 증거는 구분한다.

## 확인된 차단

- `backend/src/main/java/dev/knowledgeforge/plugins/slack/adapter/SlackReplyController.java:14`: preview/execute/acknowledge-uncertain은 본문 해석·서비스 호출 전에 403 READ_ONLY. GET과 로컬 cancel만 유지한다.
- `backend/src/main/java/dev/knowledgeforge/plugins/slack/application/SlackReplyService.java:35`: 직접 preview/execute/acknowledge 호출도 무조건 거부한다. 승인 true, 과거 digest 또는 존재하는 영수증으로 해제되지 않는다.
- `backend/src/main/java/dev/knowledgeforge/plugins/slack/adapter/SlackReplyHttpTransport.java:10`: 직접 `post()`도 lease/자격 증명 접근 없이 거부하며 HTTP 호출 구현이 없다.
- `backend/src/main/java/dev/knowledgeforge/plugins/slack/adapter/SlackHttp.java:27`: 별도 HTTP 헬퍼는 `auth.test`, `apps.connections.open`만 허용한다. 이는 인증/수신 연결용 POST이며 업무 게시 API가 아니다. Socket Provider의 `sendText`는 수신 envelope ACK다. 네트워크 bytes 전체가 0이라는 정책이나 증거로 표현하면 안 된다.

## 기존 검사 증거의 정확한 범위

- `backend/src/test/java/dev/knowledgeforge/plugins/slack/adapter/SlackReplyTransportTest.java:19`: null/잘못된 본문/직접 서비스·controller·transport 호출 차단 및 `verifyNoInteractions` 확인. 기존 Surefire 텍스트 산출물은 15개 통과다.
- `backend/src/test/java/dev/knowledgeforge/plugins/slack/adapter/SlackReplyIntegrationTest.java:65`: 실제 임시 SQLite, mock provider/transport로 새 영수증 생성 금지, 7개 과거 상태의 승인/replay 거부·원문 보존, workspace 격리, 로컬 취소를 검사한다. 기존 Surefire 산출물은 17개 통과다. 서비스 재생성은 별도 JVM 재시작과 다르다. 이 감사에서 재실행하지 않았고 Surefire 파일만으로 특정 패키지 해시와의 동일성을 인증하지 않는다.
- `frontend/src/SlackReplyPanel.test.tsx:25`: 7개 과거 상태의 GET-only controls, 전송/취소 UI 부재, 원문 그대로 렌더링, 잘못된 scope·영수증 차단을 검사한다. 이는 테스트 소스 검토이며 이번 실제 UI 실행 결과가 아니다.
- [e0 원본 실행 보고](readonly-runtime-e0e43e10-20261026.json)와 [검증 기록](readonly-runtime-e0e43e10-20261026-verification.json): 고정 `e0e43e1084177ea4d28530547cc762bce339d2de2f5721d21ce70d0ef4eeb58d` JAR, 300개 HTTP 거부, 2회 재시작, 합성 PREVIEW/SENT/UNCERTAIN 3행·dispatch count 보존, 작업 행 0. loopback-only sandbox와 거부 proxy 관측 0을 기록한다. proxy 우회/차단 시도까지 모두 계수한 것은 아니며 실제 Slack 전송이나 실제 SENT 이력이 아니다. HTTP 실행 증거는 내부 Java 직접 호출 검사를 대신하지 않는다.

## 로컬 초안·복사 및 승격 전 공백

1. `frontend/src/SlackReplyPanel.tsx:88`은 과거 영수증 조회 UI다. 새 Slack 답장 초안 편집기·저장·복사 버튼은 없다. `SlackReplyService.preview()`도 초안 저장 경로가 아니라 거부 경로다.
2. `frontend/src/SlackPanel.tsx:197`의 로컬 경로는 수신 질문을 대화에 저장하고 여는 기능이다. `frontend/src/AnswerJobsPanel.tsx:68`은 별도 요청한 AI 답변을 표시한다. 이것을 전용 Slack 초안 작성 기능으로 간주하면 안 된다. `frontend/src` 검색에서 clipboard write/execCommand 구현은 발견하지 못했다. 수동 텍스트 선택·OS 복사는 가능성일 뿐, 이 감사에서 실행하거나 검증하지 않았다.
3. 부모가 진행 중인 실제 receipt UI/restart 검토는 해당 artifact·화면·행 내용·GET-only 동작·재시작 후 동일성 증거가 확정된 뒤 별도 연결해야 한다. 이 문서에서 미리 PASS 처리하지 않는다. e0 증거를 새 `.23` JAR의 런타임 검증으로 바꾸어 기록하지 않는다.
4. 중앙 E35는 검토 시 `NOT_RUN`이다. 거부 보안 경로는 위 소스/검사로 뒷받침되지만, 전체 E35 승격에는 로컬 초안의 제품상 의미를 명확히 하고 실제 UI 증거를 연결해야 한다. 전용 초안/복사 기능을 구현 완료로 표현할 근거는 없다. 외부 업무 전송을 다시 허용할 이유는 아니다.

기존 제공자 timeout의 원인 또는 해결 여부와 무관한 감사다. 제품 소스/JAR/중앙 inventory는 수정하지 않았다.
