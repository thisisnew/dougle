# 대화형 운영 범위 후보 계약

다음 후보용 **계약 계층과 서버 Authority adapter**를 구현했다. `.25` 고정 JAR `5cf78b6441a99f893ecbaf6e81babd3e17e01e46b154f3c772e43abe138ca22a`에 포함되지 않는다. 기존 ChatThread/계획/조회 서비스, HTTP, UI, 모델 prompt는 변경하지 않았다. 연결 서비스에는 내부 메타데이터 조회와 생명주기 추적만 보강했다. 이 독립 계약이 자연 대화 조사 흐름 전체를 제공한다는 주장은 하지 않는다.

## API와 상태

- `investigation/domain/PendingOperationalScope`: id, Binding(workspaceId/threadId/parentBundleId/tool/connectionRevision/connectionGeneration), revision, typed candidate, origin, createdAt/expiresAt.
- Candidate는 Mysql(table/columns/equalities/limit) 또는 Datadog(environment/service/indexes/from/to/timeZone/limit)뿐이다. 실행 SQL·URL·host·credentials 필드가 없다. SQL 식별자는 제한된 identifier이며 조건 값은 단순 데이터 문자열이다. 값에 SQL처럼 보이는 문자가 있다고 실행문으로 해석하지 않는다. 향후 조회 adapter는 반드시 parameter binding을 유지해야 한다.
- null은 미해결 값이다. MySQL equalities=null은 조건 미정, 빈 map은 명시적으로 조건이 없는 후보라는 차이를 유지한다. 어느 쪽도 조회 승인이나 전체 행 읽기 허가가 아니다.
- `state=INCOMPLETE|FIELDS_COMPLETE`는 필드 존재 여부만 말한다. schema/allowlist/권한 검증 완료가 아니다. 모든 origin(USER_MESSAGE/MODEL_PROPOSAL/SOURCE_EXCERPT)과 상태에서 `executionAllowed()`는 항상 false다. 승인 flag/token/execute 메서드는 없다.
- collection은 defensive copy, 출력용 `toString`은 값/식별자를 숨긴다. MySQL 조건은 최대 16개·개별 1,024자·합계 4,096자, columns 최대 16개·limit 최대 200. Datadog indexes 최대 8개·limit 최대 50·절대 from/to 최대 1시간·유효한 명시적 timeZone을 사용한다. 상대 시각이나 DST 모호성을 추측하지 않는다.

## 기술 필드 누락 ≠ 사용자에게 물을 질문

`missingFields()`는 **내부 진단**이다. 이를 폼이나 “table/columns/indexes를 입력하세요”라는 질문으로 변환하면 안 된다.

- `missingInternalFields()`에는 CONNECTION/TABLE/COLUMNS/CONDITIONS/LIMIT/SERVICE/INDEXES가 들어간다. 후속 서버 schema lookup/연결 정책/계획 코드가 해결할 필드다. 기술 식별자를 비전문 사용자에게 떠넘기지 않는다.
- `missingUserContext()`는 ENVIRONMENT/TIME_WINDOW/TIME_ZONE을 중복 없이 분류한다. 이것도 자동 질문 명령이 아니다. 이미 현재 대화의 명확한 사용자 표현으로 해결할 수 있으면 후보를 채우고, 실제 모호할 때만 “어느 환경의 어느 시간대 현상인가요?”처럼 업무 맥락을 확인한다.
- **업무 대상**의 모호성은 table/service 누락만으로 추정하지 않는다. 향후 대화 해석 계층이 사용자 의도를 source-linked 형태로 설명하고, 필요할 때 “어떤 주문/업무 현상을 확인할까요?”를 묻는다. 이번 계약은 자연어 의도 해석·언어별 regex·semantic planner를 구현하지 않는다.
- 예: 사용자가 “어제 결제 실패가 늘었어”라고 하면 기술 필드 미정은 내부 작업이다. “조회할 table, columns, Datadog index를 알려주세요”라고 응답하는 통합은 이 계약의 제품 목표에 어긋난다. 확인할 환경/업무 시간대가 정말 모호할 때만 짧게 질문한다. 환경을 production으로 자동 가정하지 않는다.

## application의 신뢰 경계

`OperationalScopeClarification(Authority, Clock)`는 Spring bean이 아닌 명시적 계약 객체다. Authority는 **서버 전용 port**이며 현재 workspace 존재/소유, thread의 최신 parent, tool에 맞는 연결 revision을 검증해 Binding을 반환한다. 후속 단계에서 추가한 `ServerOperationalScopeAuthority` 구현은 아래와 같다. 사용자 JSON/모델 출력으로 Authority를 대신하면 안 된다. 계약 application 자체에는 DB/log/network/AI client 의존성이 없다.

메서드:

1. `open(workspace, thread, parent, candidate, origin)`: Authority 확인 후 revision=1, 고정 10분 TTL의 새 후보.
2. `get(workspace, id)`: 소유·TTL 및 현재 Binding 재검증. 후보 안의 값이 있어도 실행 권한으로 승격하지 않는다.
3. `revise(workspace, id, expectedRevision, currentParent, replacement, origin)`: 같은 thread/tool/connection revision에 대해 전체 후보를 **명시적으로 교체**한다. old CAS revision은 409. null을 이전 값으로 자동 보완하지 않아 오래된 업무 대상/환경을 은밀히 상속하지 않는다. createdAt/expiresAt은 유지한다.
4. `cancel(workspace, id, expectedRevision)`: 해당 로컬 후보만 제거한다. 외부 상태를 바꾸지 않는다.

연결 revision이 null→연결됨, 기존 revision→다른 revision, 연결 해제/회수된 상태로 바뀌면 기존 후보 get/revise는 실패한다. 새 Authority 확인을 거친 **새 proposal/id**가 필요하며 무승인 자동 승격은 없다. 새 parent로 보완할 때도 Authority가 최신 parent인지 확인해야 한다. 후보마다 실행 권한이 없으므로 새 proposal 역시 실행할 수 없다.

### 연결 생명주기와 영구 무효화

`connectionGeneration`은 연결되지 않은 상태에서도 필수인 서버 발급 불투명 세대 식별자다. Authority 구현은 workspace/tool 연결의 생성·연결·해제·회수·교체마다 세대를 바꾸고, 이전 값을 재사용하지 않아야 한다. revision=null은 연결 상태일 뿐 연결 생명주기의 identity가 아니다. revision 또는 generation 중 하나라도 달라지면 기존 후보를 제거한다. 이 신규 계약에는 세대 없는 호환 생성자가 없다. 아래 서버 구현의 합성 연결 변경은 실제 임시 SQLite 통합 검사로 확인했지만 설치된 `.25` 및 실제 외부 연결을 검증한 것은 아니다.

후보를 읽거나 수정하면서 Binding 불일치 또는 Authority의 404/409를 관측하면 해당 후보 객체를 영구 제거한다. 이후 연결이 원래 상태로 돌아와도 그 id는 404이며 새 proposal이 필요하다. 검사 사이에 연결 상태가 A→B→A로 돌아온 경우에는 Authority가 제공하는 새 generation으로 검출한다. Authority가 세대 변경을 누락하거나 재사용하면 관측되지 않은 ABA를 이 계약만으로 알아낼 수 없다. 유효한 검사 직후 발생한 변경까지 원자적으로 차단한다는 보장도 아니다.

소유 workspace와 CAS를 먼저 확인한다. 다른 workspace 호출은 Authority에 도달하거나 소유자의 후보를 제거하지 못한다. 제거 시 map의 객체 identity를 비교하므로 이전 검사의 지연된 실패가 이미 교체된 새 revision을 지우지 않는다. 그 새 revision도 다음 get/revise에서 최신 Authority를 다시 검증한다. 일반 서비스 장애(404/409 이외)는 오류를 반환하되 연결 변경으로 단정하지 않는다.

최대 64개 session-only 메모리 후보만 보관한다. 만료는 조회/수정 시 fail-closed이고 새 후보 생성 시 만료분을 정리한다. 수정으로 TTL을 연장하지 않는다. 시스템 시각이 생성 시각 이전으로 돌아가면 무효로 취급한다. 재시작은 후보를 복원하거나 자동 실행하지 않는다. Authority 호출은 state monitor 밖에서 수행하고 적용 직전 identity/CAS/expiry를 재검사한다. 서버 연결 변경과 후보 저장 사이의 전역 원자성을 보장하지 않으며, 향후 실제 실행은 별도 승인과 실행 직전 재검증이 필수다.

application은 알려진 credential 패턴이 있는 조건과 현재 기준 최근 7일 밖 Datadog 시각을 거부한다. 임의의 모든 비밀을 알아내는 검사는 아니다. 후보는 민감한 업무 값이 있을 수 있는 내부 자료이며 raw serialization/로그/모델 재전송을 기본 동작으로 추가하지 않는다. 기존 조회 결과의 redaction·capture·AI 전송 동의를 대체하지 않는다.

## 서버 Authority 통합 단계

`investigation/adapter/ServerOperationalScopeAuthority`는 Spring component이며 `current`는 read-only transaction이다. `LibraryStore.workspace`와 실제 `ChatThreadService.find`로 workspace 존재·해당 workspace의 thread·정렬된 마지막 bundle id를 검증한다. root/다른 workspace thread/이전 parent를 임의로 승인하지 않는다. 반환값은 기존 Binding 그대로이며 클라이언트 generation 필드를 받는 API가 없다. Authority bean 생성은 조회 실행이나 후보 자동 생성을 하지 않는다. `OperationalScopeClarification`은 여전히 수동 생성 객체이고 HTTP/UI/대화 planner에는 연결하지 않았다.

- MySQL: `PluginConnections.mysqlScopeMetadata()`가 기존 synchronized 경계에서 해당 연결 config 존재 여부와 revision을 함께 읽고, session UUID + 기존 `mysqlEpoch`를 generation으로 반환한다. save/disconnect의 기존 epoch 회전은 실패한 vault write에도 유지된다. 메타데이터 읽기 실패도 epoch를 바꾼다. 연결이 없으면 revision=null이지만 generation은 유지된다. 기존 config에 revision이 없으면 재저장을 요구하며 임의 승인하지 않는다. Notion status/token API는 호출하지 않는다. 단, 기존 PluginVault는 공유 암호화 파일을 읽는 구현이므로 물리적으로 MySQL 전용 vault 파일만 읽는다는 주장은 하지 않는다.
- Datadog: `DatadogConnections.scopeMetadata(workspace)`는 vault read와 generation을 같은 monitor에서 읽는다. save/disconnect는 성공·실패와 무관하게 finally에서 UUID를 회전하며 vault read/parse 실패도 회전한다. generation은 O(1) session 전역 값이므로 다른 workspace의 Datadog 변경도 기존 후보를 보수적으로 무효화한다. workspace 수만큼 커지는 캐시는 없다. 기존 status DTO는 바꾸지 않았으며 metadata에는 revision/generation만 있다. credentials/host/site를 반환하지 않는다.
- workspace DB 확인은 Datadog monitor 진입 전에 수행한다. monitor를 잡고 SQLite connection을 기다리는 역순 잠금을 추가하지 않는다. MySQL metadata는 네트워크 접속 검사나 schema 조회를 호출하지 않는다.
- DB의 workspace/thread 검사는 한 SQLite transaction 스냅샷이고 연결 메타데이터는 각 서비스의 일관된 별도 스냅샷이다. DB+vault 전역 원자성, 검사 후 삭제/회수에 대한 소급 철회, 하드 deadline을 보장하지 않는다. 현재는 실행 권한이 전혀 없으며 향후 실행은 별도 직전 재검증이 필요하다.
- session 재시작은 generation을 바꾸고 후보도 복원하지 않는다. 연결 변경은 현재 singleton 서비스의 save/disconnect 경로를 거쳐야 한다. 외부 프로세스의 vault 파일 교체/직접 복구나 서비스 우회 쓰기의 미관측 ABA까지 감지한다는 주장은 하지 않는다. source 검색 결과나 모델이 generation을 생성하는 경로는 없다.

MySQL의 신규 metadata 경계는 손상된 저장 JSON 등 예상치 못한 runtime 오류를 일반 `LibraryException(500)`으로 치환하며 raw message/cause를 넘기지 않는다. 연결은 있으나 revision이 없는 명시적 재연결 조건만 안전한 409를 유지한다. 합성 credential canary가 들어간 잘못된 port 값으로 exception message/toString/cause 비노출 및 실패 후 generation 변경을 검사한다. 이는 기존 모든 설정 API의 오류 정책을 일괄 변경한 것이 아니라 신규 Authority metadata 경계의 제한된 보강이다.

실제 Spring proxy + 임시 SQLite/암호화 vault를 사용하고 MySQL/Datadog/Notion provider와 검색은 fake인 통합 검사를 추가했다. 삭제 workspace, foreign thread, latest parent, 연결 revision 교체, 두 도구의 none→connected→none을 확인한다. MySQL save의 접속 검사도 mock이며 실제 DB/log/AI 호출은 없다.

## 권장 다음 통합 — 아직 미구현

1. 구현된 서버 Authority를 향후 대화 application에 연결하되 모델/source는 Candidate 제안만 할 수 있게 한다. 실행 직전 재검증과 경합 검사는 별도 실행 통합 단계에서 추가한다.
2. 자연 대화 오케스트레이터는 내부 필드 해결과 사용자 업무 맥락 확인을 분리한다. 미해결 기술 필드는 schema/read-only metadata로 해결하거나 명시적 미지원으로 멈춘다. 후보 전체 교체는 내부 구현 방식이며 매 턴 수동 keep/replace UI를 만들라는 뜻이 아니다.
3. 별도 승인 계약을 설계하기 전에는 기존 DB/log execute 서비스와 연결하지 않는다. 후속 통합도 기존 read-only 정책·revision/TTL·단일 소비·예산·결과 snapshot 및 별도 AI 전송 동의를 그대로 유지해야 한다.

## 검증

최종 광범위 회귀: `bash scripts/maven.sh '-Dtest=*,!AnswerPartialIntegrationTest,!QuestionAttachmentIntegrationTest' test`로 **1070실행/1063통과/7 opt-in 건너뜀/실패·오류0**. 기존 이름 길이 fixture 문제인 두 클래스를 제외했으므로 무제외 전체 통과는 아니다. 로그 `/tmp/dougle-operational-scope-broad.log`. 독립 재검토에서도 서버 Authority의 Workspace/최신 parent 선검증, metadata 오류 비노출, 연결 lifecycle 및 잠금 순서에서 남은 구체적 blocker를 찾지 않았다. Node 도구147개도 통과했다(`/tmp/dougle-scope-tools-final.log`). 실제 모델/회사 서비스 및 자연 대화 전체 인수는 아니다. `.25` 설치 JAR는 그대로이며 이 서버 Authority 소스는 아직 미설치다.

최종 canary 보강 후 위 Authority/계약/metadata/architecture selector에 `MysqlInvestigationServiceTest,DatadogInvestigationServiceTest,DatadogReadToolTest,DatadogInvestigationHttpTest`를 함께 실행해 **76건 통과, 실패/오류/skip 0**. 로그 `/tmp/dougle-scope-authority-final.log`. 기존 연결 조회 회귀만 먼저 실행한 32건도 통과했으며 로그는 `/tmp/dougle-scope-authority-connection-regression.log`다. 이 숫자는 중복 실행을 누적한 고유 테스트 수가 아니다. test goal만 사용했고 최종 `.25` JAR SHA-256은 `5cf78b6441a99f893ecbaf6e81babd3e17e01e46b154f3c772e43abe138ca22a`로 유지됐다.

서버 Authority 추가 후 `ServerOperationalScopeAuthorityIntegrationTest,PendingOperationalScopeTest,OperationalScopeClarificationTest,PluginConnectionsTest,DatadogConnectionsTest,ArchitectureContractTest`: **43건 통과, 실패/오류/skip 0**. 첫 실행 로그 `/tmp/dougle-scope-authority-first.log`. 이 중 신규 실제 Spring/SQLite 통합은 4건이며 provider는 모두 합성이다. 계약 클래스 자체는 이 단계에서 변경하지 않았다.

ABA 보강 후 같은 focused selector로 **23건 통과, 실패/오류/skip 0** (domain 7 + application 13 + architecture 3). 로그 `/tmp/dougle-operational-scope-aba.log`. 관측된 null→connected→null의 get/revise 영구 무효화, 관측되지 않은 연결/미연결 ABA의 generation 검증, Authority 404/409 이후 복구 불가, 다른 workspace의 무효화 금지, 지연된 이전 검증 실패가 새 revision을 제거하지 않는 경합을 합성 검사했다. `.25` JAR 해시는 아래 값으로 유지된다.

이전 검사 기록:

2026-10-26: `bash scripts/maven.sh -Dtest=PendingOperationalScopeTest,OperationalScopeClarificationTest,ArchitectureContractTest test` 실행 결과 **18건 통과, 실패/오류/skip 0** (domain 6 + application 9 + architecture 3). 로그: `/tmp/dougle-operational-scope-fixed.log`.

첫 실행은 18건 중 mock 재설정 오류 3건이었다. Mockito `when` 재설정이 기존 Authority stub을 빈 matcher 인자로 호출해 합성 stale-parent 예외를 발생시켰다. 테스트를 `doAnswer`/`doReturn`으로 바꿨으며 제품 검증을 완화하지 않았다. 최초 로그 `/tmp/dougle-operational-scope-first.log`를 보존했다.

null→connected 새 proposal 요구, 회수/교체/parent 변경, workspace 격리, 재시작, TTL, CAS 경쟁, 최대 후보 수, 내부 기술 필드와 사용자 업무 맥락 분리, 실행 불가를 확인했다. 실제 DB/log/AI/계정/사용자 자료를 사용하는 검사는 없다. unit의 Authority는 합성 port이며 실제 연결·권한 검증을 통과했다는 증거가 아니다.

test goal만 실행했으며 package/clean/verify/JAR 생성 goal을 실행하지 않았다. POM의 기존 initialize 단계는 `target/classes/static`만 정리한다. 검사 후 기존 `.25` JAR SHA-256은 `5cf78b6441a99f893ecbaf6e81babd3e17e01e46b154f3c772e43abe138ca22a`로 불변이다. 이 계약은 다음 후보 소스이며 `.25` 실행 검증 결과가 아니다.
