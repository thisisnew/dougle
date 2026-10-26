# 자연 대화 오케스트레이션: 다음 최소 구현 경계

2026-10-26 현재 소스 읽기 전용 설계. 이 문서만 추가한다. 제품/JAR·모델·권한·중앙 판정 변경과 실제 AI 호출은 없다. 아래 제안은 구현·실행 완료가 아니다.

## 현재 연결과 실제 공백

1. **일반 질문에도 이미 로컬 검색이 있다.** `conversation/application/ChatThreadService.java:94`의 create는 현재 질문을 `SearchService.searchQuestion`으로 검색하고, 유효한 최신 직접 사용자 질문이 다르면 그 결과를 current-first로 interleave한다(`:154`). 예전 누적 root query를 재사용하지 않는다. `EvidenceBundleWriter.capture`는 첨부·근거·문맥을 예산 내에서 고정한다. 검색 기능 자체를 처음 붙이는 과제가 아니다.
2. `frontend/src/ChatPanel.tsx:178`은 질문 → bundle 생성 → 근거가 있으면 기존 `sendChatAnswer` 승인/접수 경로로 이어진다. `NO_EVIDENCE`이면 답변 실행을 시작하지 않는다. 서버 `AnswerJobService.submit/run`은 고정 bundle/prompt digest·모델·승인을 재확인하고 일회 소비한다. 생성 중 다시 도구를 실행하거나 근거를 추가하지 않는다.
3. 일반 질문 검색은 **ConstrainedToolExecutor 밖의 직접 호출**이다. `investigation/application/LocalInvestigationTools.java`의 workspace/local/workspace-search binding, 취소·시간·출력 예산과 정상 질문 경로가 공유되지 않는다. 정상 질문의 HTTP abort를 서버 검색 취소로 연결한 보장도 없다. 현재 hit 제한/최종 prompt 제한과 실행 시간 제한은 서로 다른 문제다.
4. `InvestigationPlanService.run`은 별도 동의를 받은 계획 모델 1회 후 SEARCH이면 등록된 로컬 도구 최대 1회만 실행한다. STOP/PROPOSE_MYSQL_READ/PROPOSE_DATADOG_READ는 운영 조회가 아니다. `OperationalToolProposal.executionAllowed`는 항상 false다. 기존 계획 승인을 정상 질문 승인으로 대체해 자동 호출하면 **추가 모델 호출·동의 범위 확대**이므로 이번 최소 단계에서 하지 않는다.
5. `InvestigationPlanService.capture:126`은 저장된 tool output을 그대로 bundle로 만드는 것이 아니라 `threads.createWithRetrieval`로 **다시 검색**한다. 따라서 도구 출력과 나중의 고정 근거가 같은 검색 snapshot이라는 보장은 없다. 캡처 때의 새 근거/승인을 사용한다는 현재 의미는 유지하되, 이를 단일 실행 provenance라고 표현하지 않는다.
6. MySQL/Datadog는 별도 preview/execute/session-only result, 명시적 capture → 로컬 첨부 경로다. 대화 목표를 서버가 명확히 한 뒤 한정된 조회를 실행하고 설명하는 연결은 아직 없다. UI 도구상자를 숨기는 것만으로 이 연결이 생기지 않는다.

## 권장 첫 vertical slice: 일반 질문의 한정된 로컬 검색 → 기존 답변

사용자는 평소처럼 질문한다. 별도 도구 선택·query 편집·검색 결과 capture 버튼을 요구하지 않는다. 다만 이 단계는 **결정적 검색 오케스트레이션**이며 모델이 스스로 여러 단계 조사하는 agent 구현이라고 부르지 않는다.

### 구현할 정확한 경계

- `search/application`에 `ConversationRetrieval` port와 typed request/result를 둔다. 요청은 서버가 고정한 workspace, 현재 질문, 선택적 최신 직접 질문, requestId/cancellation이다. 결과는 status(OK/EMPTY/DENIED/TIMEOUT/FAILED/CANCELLED), immutable `SearchIndex.Hit` 목록, 실제 실행한 query 목록, truncated, policyVersion이다. raw tool output JSON을 파싱해 근거를 재구성하지 않는다.
- 구현은 `investigation/adapter`에 두어 기존 `ConstrainedToolExecutor`와 workspace 검증을 재사용한다. conversation/application에서 adapter를 import하지 않는다. 기존 `LocalToolExecutorFactory`가 필요로 하는 SearchService/LibraryStore만 의존하고 ChatThreadService를 주입하지 않아 순환 DI를 만들지 않는다.
- 현재 질문과 최신 직접 질문의 **최대 두 개, 동일 질문 중복 제거**, current-first merge를 그대로 보존한다. 한 사용자 요청에 공유 10초 local-search deadline, maxCalls=2; 두 호출 각각 10초로 총 20초가 되지 않도록 한다. 원문 evidence budget·실제 JSON/prompt 20,000자 guard는 기존 writer가 최종 결정한다. 도구 표시 문자열 budget이 typed hits의 메모리/문자 budget을 대신하지 않게 별도 상한을 둔다.
- 기존 도구는 출력 문자열만 반환하므로 typed snapshot 전달을 위한 내부 결과 통로가 필요하다. 호출별 immutable hit snapshot을 성공적으로 완료한 invocation에만 연결하고, TIMEOUT/CANCELLED 후 늦게 끝난 worker의 값은 절대 publish하지 않는다. 공유 mutable hit list나 모델이 제공한 entry ID를 writer에 신뢰 전달하지 않는다. 기존 텍스트 도구 API의 호출자는 유지한다.
- `ChatThreadService`에서 준비(기존 요청 조회·부모/첨부/문맥 선택) → **DB transaction 밖에서 bounded search** → 짧은 capture transaction으로 나눈다. capture 직전에 workspace, 부모 CAS, 자료 revision, 첨부 scope, 요청 내용 동일성을 다시 검사한다. 검색 중 DB transaction/서비스 monitor를 잡지 않는다. 결과를 소비하면서 다시 검색하지 않는다. writer의 현재 원문 revision 재검증도 유지한다.
- 검색 실패를 EMPTY/근거 없음으로 숨기지 않는다. TIMEOUT/FAILED/CANCELLED이면 answer approval/job을 만들지 않는다. EMPTY가 실제 성공한 무결과일 때만 NO_EVIDENCE bundle과 “어떤 자료/대상을 더 알려주면 되는지” 한정된 안내를 보여준다. 비어 있는 검색으로 업무 사실이나 장애 원인을 추정하지 않는다.
- 서버 취소를 추가한다면 workspace+requestId에 한정된 작은 in-flight registry를 사용한다. UI abort만으로 취소됐다고 표시하지 않는다. 취소 endpoint는 해당 local-search flag만 바꾸며 모델/DB/log 호출 권한을 갖지 않는다. 첫 단계에는 새 durable orchestration table이 필요 없다: 재시작 시 in-flight 작업은 사라지고 자동 재실행하지 않는다. 응답 유실 시 먼저 같은 requestId로 이미 커밋한 bundle을 확인한다. 동시 동일 ID는 내용을 비교하고 하나만 실행/커밋한다. registry 용량·TTL/close도 제한한다.
- bundle이 고정된 후 **기존 승인·일회 AnswerJob 경로만** 호출한다. 최대 기존 answer 1회, planner 0회, remote operational calls 0회. 이미 승인된 prompt에 검색 결과를 사후 삽입하지 않는다.

### 저장/호환성

기존 ChatContext의 query 목록·extractive turnKind/source digest와 EvidenceBundle payload/digest를 유지한다. 새 실행 outcome/version을 저장한다면 optional NON_NULL 메타데이터로 추가하고 구 JSON digest를 바꾸지 않는다. 캡처 실패는 성공 bundle로 기록하지 않는다. 새 필드에 reference가 생기면 archive validator/export/import remap 회귀를 함께 변경한다. 첫 slice에서 DB migration 없이 가능하나 durable 재시작 진행 복원이 필요해지면 별도 orchestration table 설계/승인이 필요하다. 기존 bundle을 진행 상태 저장소로 덮어쓰지 않는다.

## 다음 단계: 대화형 운영 범위 확인 — 아직 실행에 연결하지 않기

- 계획 결과/사용자 발화는 **후보 정보**다. source excerpt, 이전 답변, extractive memory는 운영 permission이 아니다. “그쪽”, “아까 환경”, “최근”을 코드가 생산 환경/시간 범위로 추론하지 않는다. 언어별 수정 키워드 regex도 도입하지 않는다.
- 서버의 `PendingOperationalScope` 후보 계약: workspaceId, threadId, parentBundleId, revision, toolKind, connectionRevision, typedCandidate, missingFields, expiresAt. secret·host·임의 SQL/URL은 넣지 않는다. 운영 scope는 장기 user-history와 분리한다. 변경/보완 발화는 후보 revision을 새로 만들고 과거 승인을 폐기한다.
- MySQL은 실제 선택된 연결/DB에 대한 allowlisted table/columns/equalities/limit를 기존 `MysqlReadClient.Request` 계약으로 검증한다. 향후 application port에는 adapter DTO를 직접 노출하지 않는다. 환경 label이 연결 설정에 검증 가능하게 없으면 production/staging으로 꾸며 쓰지 않는다. Datadog는 `Query(connectionRevision, environment, service, indexes, from, to, limit)`의 정확한 allowlist와 절대 시각/시간대를 확인한다. 누락된 필드만 짧은 대화 질문으로 요청한다. 임의 자연어를 정확한 scope로 해석하는 기능은 현재 모델 계약에 없으므로 이 부분을 구현 완료로 간주하지 않는다.
- scope가 완성되면 일반 대화 안에서 서버가 만든 정확한 범위 설명을 확인받는다. 수동 SQL/도구 양식이나 매 턴 keep/replace ledger를 강요하지 않는다. 그러나 자연스러운 UX를 이유로 **기존 명시적 조회 승인**을 생략하지 않는다. 모호한 회신은 실행하지 않고 다시 확인한다.
- 실행은 기존 Mysql/Datadog preview/execute service를 위임하는 application port로만 연결한다. application→adapter 직접 import를 추가하지 않는다. 기존 connection revision/epoch·TTL·workspace 재검증·단일 요청 소비·취소·row/byte/time cap·redaction을 유지한다. model이 등록 tool/SQL/endpoint를 선택하거나 넓힐 수 없다. 조회 전용은 승인으로 해제되지 않는다.
- 조회 승인과 **조회 결과의 AI 전송 승인**은 별개다. 먼저 정확한 provenance/잘림을 포함한 immutable local snapshot을 확인시킨다. 기존 OperationalCaptureService의 3,000자 exact-preview/digest/attachment 경로를 재사용하고, 해당 대화의 해당 결과만 첨부한다. 전역 storage를 자동 stage하지 않는다. capture retry는 GET 확인 우선, AI에 보낼 새 bundle은 새 승인으로 고정한다. 이 경계 변경 없이 결과를 몰래 answer prompt에 붙이는 자동화는 불가하다.

## 구현 전 합의할 회귀 기준

1. `ChatThreadIntegrationTest.vagueFollowupWithNonemptyDistractorsStillRetrievesLatestDirectQuestionEvidence`: 현재 질문이 무관한 nonempty hits를 내도 직전 직접 질문의 Strategy 근거를 찾는다. 반대로 explicitNewTopic은 current-first, 누적 root 부활 금지. 기존 capture는 새 사용자 질문으로 세지 않는다.
2. shared budget: 질문 2개가 10초를 나눠 쓰고 identical invocation은 1회만 실행. localSearch TIMEOUT/FAILED/CANCELLED와 EMPTY를 구분. 늦은 worker completion은 bundle/answer job 생성 0. 취소/상태 조회가 느린 검색을 기다리는 공유 monitor를 갖지 않는다.
3. 검색 도중 workspace 삭제·부모 추가·source 수정·attachment 삭제/revision 변경 → capture 실패 또는 명시적 새 요청. 검색 A와 캡처 B가 조용히 섞이지 않음. 정상 검색은 query별 1회만 호출하고 capture 재검색 없음.
4. duplicate request/retry/응답 유실/동시 탭 → 기존 bundle 반환 또는 409, answer dispatch 최대 1. 프로필/모델 변경 시 기존 승인 재사용 금지. 검색 중 재시작은 자동 AI 재개 없음.
5. `EvidenceBundleWriter`/ContextPrompt 기존 escaped Unicode·32 whole questions·최근 답변·첨부·20k answer/plan wrapper 예산과 legacy null serialization/digest/archive remap 회귀를 유지한다.
6. 향후 scope: source 속 “production 조회” 지시, 오래된 환경, 다른 workspace connection, 만료/회수된 revision, 상대 시간 모호성 → 실행 0. 최신 사용자 정정은 후보만 갱신하고 기존 승인 무효화. 완성 scope 승인에도 SQL writes/Slack posts/browser mutations 0.
7. 향후 capture: known password/token redaction 후 byte cap·provenance truncation 표시, EMPTY를 “해당 범위 결과 0”으로만 설명. local capture만으로 AI 호출 0, 다른 대화 결과 자동 첨부 0, 승인 후 snapshot bytes와 저장 bytes 일치.

우선 구현 승인 대상은 첫 local-only slice다. 모델이 만드는 추가 검색·다단계 DB/log 조사·새 semantic scope parser는 후속 계약으로 분리한다. 자연 대화 목표를 유지하되 현재보다 많은 모델 호출이나 운영 권한을 암묵적으로 추가하지 않는다.
