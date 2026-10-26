# 일반 질문의 제한된 로컬 검색

2026-10-26 23:00 KST: 아래 구현은 **0.1.25 Intel 설치본에 반영**했다. [새 JAR의 실제 로컬 검색·이어 질문·재시작 검사](evaluations/retrieval-runtime-0.1.25-20261026.json)와 [백업·설치 증거](evaluations/local-install-0.1.25-20261026.json)를 따른다. 아래 `.24에 미포함`은 소스 작업 당시 기록이다. 후속 운영 범위 후보 계약은 여전히 별도 미설치 소스이며 이 검색 개선과 구별한다.

최종 광범위 회귀: 2026-10-26 `bash scripts/maven.sh '-Dtest=*,!AnswerPartialIntegrationTest,!QuestionAttachmentIntegrationTest' test`로 **1041실행/1034통과/7 opt-in 건너뜀/실패·오류0**. 기존 Workspace 이름 길이 fixture 문제인 두 클래스를 명시적으로 제외했으므로 무제외 전체 통과는 아니다. 로그 `/tmp/dougle-conversation-retrieval-broad.log`. 도구 단위 검사는 `node --test scripts/*.test.mjs` **143/143통과** (`/tmp/dougle-conversation-tools-final.log`). 독립 재검토에서도 앞선 네 경계 수정의 남은 구체적 blocker는 발견하지 않았다. 실제 모델 품질·운영 조사·새 패키지 설치 인수를 대신하지 않는다.

다음 후보용 소스 변경이다. `.24`의 고정 JAR/설치본에 반영되었다는 뜻이 아니다. 부모 승인 후 아래 focused `test`만 실행했다.

## 실제 연결

`ChatThreadController → ChatThreadService.create → ConversationRetrieval → BoundedConversationRetrieval → SearchService.searchQuestionCandidates → 로컬 SearchIndex`로 연결한다. 검색 후 `EvidenceBundleWriter`가 서버 DB의 workspace·원문 상태·textHash를 다시 확인하고 기존 immutable bundle을 생성한다. 기존 질문 제출 UI와 answer approval/job 경로는 변경하지 않는다. 추가 planner/answer 호출, 운영 DB/log 조회, 외부 권한은 추가하지 않았다.

- 현재 질문 우선, 유효한 최신 직접 사용자 질문 보조의 최대 2개 query를 current-first interleave한다. 같은 query는 한 번만 실행하고 entry ID를 중복 제거한다. 누적 root query나 언어별 정정 규칙을 도입하지 않는다. SEARCH_CAPTURE의 기존 query 순서·turnKind는 유지한다.
- 두 query는 하나의 10초 deadline을 공유한다. worker pool은 전역 2개, 대기 queue 없음이며 처리 슬롯이 없으면 DENIED다. interrupt를 무시하는 색인/모델 연산이 남아도 반복 요청으로 worker를 무한 생성하지 않는다. JVM 내 강제 thread 종료를 보장하지 않는다.
- 결과는 immutable typed Hit 목록이다. 표시용 JSON/텍스트를 파싱하거나 재검색해서 근거로 바꾸지 않는다. 정상 lexical producer에 맞춰 query당 raw 30건(두 query 최대 60건)·raw 문자열 합계 262,144 UTF-16자를 먼저 제한하고, 중복 제거 후 unique 최대 60건·출력 문자열 합계 131,072자를 별도 제한한다. hit당 domain은 20개다. 동일 30건을 두 query가 반환해도 중복 전 48건 한도로 오거부하지 않는다. 초과하면 DENIED이며 일부 결과를 잘라 OK/EMPTY라고 속이지 않는다. 기존 근거 5개/문자 예산·최종 answer/plan prompt 20,000자 guard는 writer가 그대로 적용한다.
- EMPTY만 실제 무결과다. DENIED/TIMEOUT/FAILED/CANCELLED는 명시적 오류이며 새 bundle/answer job을 만들지 않는다. 실패 메시지에 원본 예외·경로·비밀을 포함하지 않는다.

## 트랜잭션과 취소 경계

보통 HTTP create는 준비 transaction → transaction 없는 검색 대기 → capture transaction으로 진행한다. capture에서 부모/문맥/자료 scope를 재검사하고, 쓰기 전후 및 beforeCommit에 deadline/취소를 확인한다. 취소되거나 timeout된 검색 worker는 bundle store를 호출하지 못한다. 동일 workspace/request ID 동시 실행은 409, 완료된 동일 요청 replay는 기존 bundle을 반환하며 검색하지 않는다. in-flight 요청은 최대 32개다.

현재 선택 가능한 후보(body match이며 아직 5개/문자 한도 전)의 원문 삭제/textHash 변경은 409로 실패한다. 이미 선택 범위를 벗어난 stale/deleted 후보까지 전체 질문을 실패시키지 않으며 기존 ITEM_LIMIT/REMOVED 등 제외 기록을 유지한다. 다른 workspace의 후보는 선택 순서와 무관하게 scope 오류로 거부한다. 이 엄격한 선택 검증은 새 conversation capture에만 적용하고 기존 독립 bundle capture의 제외 규칙은 유지한다.

기존 Slack 수신 저장·계획 capture가 이미 transaction을 연 경우에는 그 transaction에 참여해 전체 rollback 의미를 유지한다. **이 경우 외부 transaction/monitor를 검색 동안 해제했다고 주장하지 않는다.** SQLite pool이 1개이므로 worker에서 SearchService의 DB 필터까지 실행하면 연결 획득 교착이 생긴다. 새 candidate 메서드는 로컬 색인만 읽고 DB 필터/원문 검증은 capture 쪽으로 이동했다. ConstrainedToolExecutor의 문자열 출력 계약을 억지로 typed evidence로 역파싱하지 않고, 고정된 단일 로컬 operation만 구현하는 bounded typed port를 사용한다. 모델이 tool registry/URL/SQL을 전달할 입구는 없다.

시간 예산은 검색 시작 후 캡처 허용 판단에 적용된다. 준비 DB 조회·pool 획득·물리 commit 자체가 항상 10초 안에 끝난다는 hard wall-clock 보장은 아니다. 마지막 검증 이후 동시 삭제/네트워크 전달까지 되돌리는 보장도 없다. HTTP 연결 종료가 자동으로 Java interrupt를 전달한다는 주장도 하지 않는다. 이번 단계의 취소는 worker/caller interrupt와 내부 Attempt flag이며 새 UI 취소 버튼/서버 취소 endpoint는 추가하지 않았다. 재시작 시 검색을 자동 재개하지 않는다.

payload/schema/prompt version을 새로 만들지 않았으므로 기존 JSON bytes·digest·archive 참조 계약은 유지한다. 기존 legacy/archive 회귀를 다시 실행해야 한다. 추가 검색 결과가 승인된 answer prompt에 사후 삽입되는 경로도 없다.

## 검사 범위

- `BoundedConversationRetrievalTest`: current-first/중복 제거, 최대 query 수/크기, typed 출력 제한, 두 번째 검색 실패 시 전체 폐기, 공유 deadline/늦은 결과 폐기, 취소 flag, interrupt를 무시하는 worker 2개 이후 admission 거부.
- `ConversationRetrievalIntegrationTest`: 실제 Spring·임시 SQLite와 fake model, 정상 질문 연결/재시도, 모든 실패 상태 무저장, EMPTY 구분, 캡처 중 취소 rollback, 삭제된 원문/workspace, 늦은 worker 무저장, 검색 중 부모 CAS와 중복 요청, 기존 외부 transaction rollback/단일 pool 호환.
- `ChatThreadIntegrationTest`의 기존 E01 distractor/직전 질문 검색, 주제 변경·root 미상속, legacy bytes, 긴 문맥 예산·삭제 경쟁을 유지한다. 검색 spy 지점만 DB 없는 candidate operation으로 이동했다.
- 권장 lease selector: `BoundedConversationRetrievalTest,ConversationRetrievalIntegrationTest,ChatThreadIntegrationTest,InvestigationPlanIntegrationTest,SlackInboxIntegrationTest,SlackInboxServiceTest,WorkspaceArchiveIntegrationTest,ArchitectureContractTest`. `test`만 사용하며 `clean/package/verify`와 JAR 쓰기는 금지한다.

### 2026-10-26 focused 실행 결과

첫 실행 **112개 통과, 실패/오류/skip 0**. 위 selector 그대로 실행했으며 신규 단위 8개·통합 13개, 기존 ChatThread 24개·계획 19개·Slack 통합 11개/단위 17개·archive 17개·architecture 3개다. 최초 로그를 보존했다: `/tmp/dougle-conversation-retrieval-first.log`. 실패 후 재실행은 없었다. 실제 AI/외부 서비스는 호출하지 않았다.

`bash scripts/maven.sh -Dtest=BoundedConversationRetrievalTest,ConversationRetrievalIntegrationTest,ChatThreadIntegrationTest,InvestigationPlanIntegrationTest,SlackInboxIntegrationTest,SlackInboxServiceTest,WorkspaceArchiveIntegrationTest,ArchitectureContractTest test`만 실행했다. 부모가 추가한 initialize 단계의 `clean-frontend-assets`는 `target/classes/static`만 지운 뒤 고정 dist를 복사한다. Maven `clean` phase/package/verify/repackage는 실행하지 않았다. `backend/target/knowledge-forge-0.1.0.jar`의 실행 중/후 SHA-256은 `b5b59534ed02f1ad342a64d55f6e40cff03947997af2c07340b5b9bd85473d47`로 동일했다. class output은 다음 후보 소스로 컴파일됐지만 JAR는 이전 `.24` 그대로다. 새 후보 packaging/runtime·설치 검증이나 실제 모델 대화 품질 PASS를 뜻하지 않는다.

### 독립 리뷰 보강 결과

첫 112개 검사에 없던 정상 producer 경계(두 query 각각 동일 30건)를 독립 리뷰에서 지적했다. 중복 전 48건 제한을 raw/unique 예산으로 분리했고, 서로 다른 30+30건도 정상 처리한다. 과도한 raw 입력·중복 제거 후 과도한 출력은 여전히 명시적으로 거부한다. 또한 전체 후보 선검증을 제거하고 conversation writer의 선택/제외 단계로 옮겨, 이미 5개를 선택한 후의 stale/deleted 후보가 질문 전체를 실패시키지 않게 했다.

동일 selector 보강 실행 **117개 통과, 실패/오류/skip 0**: retrieval 단위 10개·통합 16개, 나머지는 위와 같다. 새 회귀는 동일/서로 다른 30건 쌍, raw/unique 문자 예산, 비선택 stale/deleted 제외 기록, foreign-workspace hit 거부, 실제 10초 deadline 경과 후 외부 transaction commit rollback을 포함한다. 로그 `/tmp/dougle-conversation-retrieval-review.log`. 앞선 112개와 중복 합산하지 않는다. 두 로그 모두 보존했고 이번에도 Maven `test`만 실행했으며 `.24` JAR의 위 해시는 그대로다. 최초 suite가 통과했다는 사실이 당시 경계 버그가 없었다는 증거는 아니다.
