# 아키텍처

## 수동 운영 조회 UI 제거 — 2026-10-26

사용자 요청에 따라 `ChatPanel`의 운영 조회 패널, 조사 제안에서 수동 양식을 여는 경로, 플러그인 설정의 DB 행/로그 조회 화면을 제거한다. 연결 설정·Slack 수신함·일반 첨부·내부 읽기 전용 API는 유지한다. 아래 수동 양식 연결 설명은 이전 구현 이력이다. 계획의 운영 도구 제안은 실제 조회 실행이나 자동 조사 완료를 의미하지 않는다.

## 연속 대화 작업본 — 미배포

다음 후보의 일반 질문은 `ChatThreadService → ConversationRetrieval → BoundedConversationRetrieval`을 거친다. 현재 질문과 유효한 직전 질문을 최대2회 로컬 검색하고 공통 시간·결과·동시 실행 예산을 적용한다. 검색 후 서버가 Workspace와 원문을 재검증해 불변 근거를 저장한다. 실패를 무결과로 숨기거나 자동 AI 재전송하지 않는다. 설치된0.1.24에는 포함되지 않으며 운영 도구 자동 선택/실행 구현도 아니다. [검증과 제한](conversation-local-retrieval.md).

최신 정책은 Slack을 포함한 모든 외부 업무 시스템의 조회 전용이다. `SlackReplyController`와 `SlackReplyService`는 전송 미리보기·실행·확인을 `403 READ_ONLY`로 거부하고, `SlackReplyHttpTransport`에는 외부 전송 구현이 없다. 사용자 승인으로 이 제한을 해제하지 않는다. DB v28의 `slack_reply`와 화면은 과거 영수증 조회를 위해 보존한다. 앱 내부 대화·색인·설정 저장과 조회용 인증 수립은 허용하며, 원본 자료나 외부 메시지 변경은 허용하지 않는다. 아래 과거 전송 설명보다 [외부 조회 전용 정책](external-readonly-policy.md)이 우선한다. 소스 검증과 설치된 패키지 검증은 구별한다.

최신 DB v27 작업본은 별도 계획 동의로 `PlanModel → SEARCH/STOP → ConstrainedToolExecutor → 로컬 검색`을 실행하며, 결과로 새 불변 근거/답변 승인을 만든다. V3 계획 미리보기는 검색 결과가 없는 질문도 지원하되 변경된 기존 근거는 거부한다. MySQL 제한 조회는 `MysqlReadPanel → MysqlInvestigationController/Service → MysqlReadClient`이며 명시적 호스트/DB 확인·조건/열 범위·서버 읽기 전용 트랜잭션·단일 사용 미리보기를 적용한다. DB 결과는 자동 모델 전송하지 않는다. v27은 격리 브라우저 조사, Slack 수신함과 대화 연결, 자료·대화 아카이브 가져오기 영수증을 보관한다. 아래 미연결·DB v25 설명보다 최신이며 자세한 현재 범위는 `expert-implementation.md`를 따른다.

`ChatPanel → /chat-threads → ChatThreadService`는 불변 근거 JSON의 `chatContext`에 thread/parent/request ID와 제한된 최근 문맥을 저장한다. 이력은 대화별 조회·삭제, 순서는 parent 연결 기준이다. `EvidenceAnswerPreview`는 과거 bundle digest를 재검사하고 문맥을 승인 prompt에 포함한다. 현재 추출형 문맥은 최대32개 질문과 최근5개 답변 발췌를8,000자 예산 내에서 보존하며 생략 여부를 표시한다. AI 의미 요약이나 무제한 기억은 아니다. 현재 DB는 v28이며 [문맥 계약](conversation-context.md)과 [구현·검증 및 남은 한계](expert-implementation.md)를 따른다. 계획 모델의 로컬 검색 실행은 연결되어 있으나 MySQL·Datadog 제안은 자동 운영 조회가 아닌 별도 수동 조회 양식으로 이어진다. 아래 독립 질문·문맥 미전송·도구 실행기 미연결 설명은 이전 버전이다.

## 현재 수집·검색 경로 (DB v25)

SourceService는 디스크 스냅샷을 수집하고 파일별 분석을 `source_analysis_checkpoint`에 저장합니다. 읽기·분석 실패는 `source_file_failure`에 남기며 이전 성공 자료를 보존합니다. `ProcessingProgress` → Source 진행 API → `SourceSyncProgress`는 실제 파일명·바이트·페이지·분할 상태를 표시합니다. 원본 발행 전에 취소할 수 있으며 다음 동기화는 완료 분석을 재사용합니다.

`TextChunks`는 문단·코드 선언 경계와 실제 행 번호를 보존합니다. `LocalSearchModel`은 Mac 번들에 동봉된 고정 해시 모델을 앱 시작 후 비동기로 준비합니다. `LuceneSearchIndex`는 키워드/벡터 후보를 조각 단위로 재평가한 후 문서 중복을 제거합니다. 준비/실패 중 새 질문은 UI와 API에서 차단하며, 조용히 키워드 전용으로 낮추지 않습니다. `EvidenceWindow`는 근거 고정 시 해시가 일치하는 원문에서 앞뒤 문맥을 확장합니다. [검색 설계](internal-search.md) · [처리 한도·실제 검증](ingestion-retrieval-upgrade.md).

BrowserPluginHttp는 OAuth form과 JSON 요청에 UTF-8을 명시합니다. Apache FORM_URLENCODED의 기본 ISO-8859-1은 Notion token endpoint에서 실제 `invalid_request` 400을 유발하여 가상 client/code로 재현 후 수정했습니다. 토큰 교환/갱신의 보안 검증·리소스 지정은 유지합니다.

ChatPanel은 완료 답변의 코드 블록 밖 `[R번호]`와 일치하는 근거만 references로 전달합니다. compact 답변이 SUCCEEDED이고 화면 출력까지 끝난 뒤 표시합니다. 후보 묶음 전체를 출처로 보여주지 않습니다. 인용 번호 필터링이며 답변의 사실성을 별도 보증하는 기능은 아닙니다.

플러그인은 설정 하위입니다. BrowserPluginConnect → PluginController → BrowserPluginConnections가 DCR/PKCE 브라우저 승인·loopback 콜백·인증 회전/취소를 담당합니다. BrowserPluginReader → PluginMcpSession은 Notion/Atlassian의 고정된 읽기 도구로 등록 링크를 수집하며 UrlCollector의 기존 검색 색인 흐름을 재사용합니다. MySQL과 이전 내부 토큰 API는 PluginConnections/MysqlClient/NotionClient 호환 경로입니다. PluginVault의 AES-GCM·0700/0600·원자 교체를 유지합니다. DB v24 변경 없음. 실제 외부 계정은 미검증입니다. [계약·한계](plugins.md).

연속 질문은 ChatPanel의 턴 목록에 누적하고 bundle ID를 React key로 유지합니다. 같은 대화의 후속 질문은 검증된 제한 문맥을 포함하며 이력 열기만으로 AI를 호출하지 않습니다. SourcePanel은 자료 등록 직후 반환된 ID로 동기화를 시작하고 진행 API를 조회합니다. 완료까지 탐색 잠금을 유지하며 첫 동기화 실패도 목록에서 확인·재시도할 수 있습니다. 이전 클라이언트의 `sync:true` 등록 API는 호환 경로로 유지합니다. 파일 업로드는 LibraryService의 자동 분석과 Controller의 색인 갱신을 사용합니다.

자료 변경 작업의 busy를 App에 공유하여 탭·설정·Workspace 선택/생성·AI 연결과 파일 변경을 잠급니다. 종료/실패 후 해제합니다. 개별 파일 진행은 약 700ms 간격으로 조회하며 예상 시간은 만들지 않습니다. 최근 성공 시각은 Workspace별 UI 캐시 및 Source syncedAt을 사용하고 일괄 실패 시 캐시를 갱신하지 않습니다. 캐시는 승인 판단에 사용하지 않습니다.

빈 Workspace 탐색: 질문 기록 탭은 세션 준비 후 Workspace 유무와 무관하게 열립니다. QuestionGrid는 nullable workspaceId를 받아 null에서는 빈 목록만 표시하며 조회/삭제 API를 호출하지 않습니다. 대화의 페이지 제목(h1)과 브랜드(h2)를 구분하고 빈 시작 화면은 브랜드/만들기 버튼만 제공합니다.

0.1.20 후속: Workspace 전환은 활성화 PUT→세션 확인→새 자료 GET 완료까지 스켈레톤과 선택 잠금을 유지합니다. 이전 화면은 전환 시작 시 unmount하고 이전 요청 응답은 AbortController로 무시합니다. 메인 상단의 중복 Workspace 이름은 제거했습니다. GET `/api/workspaces/{id}/analysis-failures`는 추출 오류/미지원 파일과 상위 폴더의 메타데이터만 한 번에 반환하며 자료 본문은 포함하지 않습니다.

업데이트 IPC는 확인 작업을 시작한 뒤 즉시 반환하고 UI가 상태를 조회합니다. 사이드바 하단은 설정 버튼이며 업데이트 확인은 설정 화면 안에 표시합니다. DesktopUpdate는 다른 화면에서 hidden 상태로 유지하여 자동 확인을 계속하고 새 버전을 설정 버튼의 점으로 알립니다. 새 버전 없음은 `current`로 유지하여 ‘최신 버전입니다’를 표시합니다. 오류는 재시도 가능한 상태이며 단순 확인 결과에 차단형 네이티브 팝업을 띄우지 않습니다. 실제 다운로드/설치 확인·서명/CPU/버전 검사는 유지합니다.

0.1.20: ChatPanel submit→sendChatAnswer가 기존 근거/미리보기/승인/작업 API를 순서대로 호출합니다. 사용자 질문 전송을 실행 동의로 처리하고 별도 동의 UI를 생략합니다. digest·모델 검증/CAS·일회 승인·요청 멱등성은 유지합니다. compact AnswerJobsPanel→StreamingAnswer로 수신한 글을 표시하며 SSE 상태는 숨깁니다. QuestionGrid가 기존 근거 삭제 API와 공통 DeleteConfirmation을 사용합니다. DB v24 유지.

0.1.19: `EvidenceBundleService → SearchService.searchQuestion → SearchIndex.searchQuestion`으로 자연어 질문용 OR 검색(최소 한 토큰 일치)을 분리합니다. 일반 `search`는 기존 AND를 유지합니다. `LibraryService.syncFiles`는 Workspace 보관 업로드를 재분석하고 Controller에서 색인을 갱신합니다. Source 관리 Entry는 제외합니다. PDF 미리보기는 저장된 추출문을 사용합니다. DB v24·승인 계약은 유지합니다.

0.1.18 파일 추가 피드백: App의 업로드 상태를 SourcePanel 버튼과 탐색기 진행 행에 공유합니다. 네트워크 응답까지 파일명을 표시하며, 성공 후 기존 목록 재조회 상태로 넘어갑니다. ref 가드로 동일 시점의 중복 요청을 막습니다. 진행률 추측·API/DB 변경은 없습니다.

0.1.17 DB v24(로컬 설치 완료, GitHub 미공개): `source.created_at`은 신규 등록 시 기록하고 과거 NULL은 보존합니다. `workspace_source_polling.interval_seconds`는 기본 3600, 60–86400초입니다. `WorkspacePollingToggle → SourceController → SourceService → JdbcSourceStore`가 CAS로 설정을 저장합니다. 명시적 주기 변경은 기존 자료에 원자적으로 적용하고 신규 자료는 정책을 상속합니다. OFF는 주기를 지우지 않고 다음 실행 시각만 제거합니다. 이전 클라이언트가 주기를 생략하면 기존 개별 주기는 유지합니다.

0.1.16의 자료 등록은 `SourcePanel → sourceName → 기존 Source API`로 이름을 자동 생성하고 신규 domain을 빈 문자열로 보냅니다. 이름/분류 입력은 없으며 기존 데이터와 API 계약은 유지합니다. [검증·범위](release-0.1.16-report.md).

0.1.14는 `SourcePanel.renderLibrary`로 연결 자료 행과 App의 업로드 탐색기를 통합합니다. 연결 루트는 중복 제거하고 폴더 선택 시 기존 Entry API로 탐색합니다. 전체 동기화는 기존 Source별 POST를 순서대로 호출하며 실패 후에도 다음 자료를 처리합니다. 자동 갱신은 `WorkspacePollingToggle → SourceController → SourceService → JdbcSourceStore`를 통해 DB v23의 Workspace 정책을 저장하며 새 Source가 이를 상속합니다. AI/승인 계약은 바꾸지 않습니다. [화면 설계](library-simplification.md).

0.1.13의 사용자 브랜드는 **Dougle · 더글**입니다. `Wordmark`를 대화/사이드바에서 공유하고, 자료·질문·승인·인증 모델은 변경하지 않습니다. [화면·아이콘 설계](dougle-brand.md).

0.1.12는 `App → 설정 → SourceSettings / Workspace 관리`로 설정을 분리합니다. 자료 추가는 파일·폴더·웹 링크·GitHub 한 곳, 기록은 `QuestionGrid → 선택한 bundle ID → ChatPanel`입니다. 목록은 요약 GET만 사용하고 AI/답변 본문을 자동 조회하지 않습니다. 폴더는 `folderPicker.ts → Tauri select_source_folder → 폴더 전용 네이티브 선택기`로 연결하며 현재 앱 origin·main 창에만 명령을 허용합니다. 서버 등록/동기화 모두 실제 폴더를 검증합니다. [코드 대조](folder-settings.md).

질문 첨부는 별도 `attachment` 모듈(Reader/Store 포트, ImageIO·Tika/JDBC 어댑터)입니다. 전역 수집/색인과 분리하며 기존 불변 근거·일회 승인·답변 작업에 연결합니다. 이미지는 Codex `localImage` 입력입니다. [구조·DB v22·제약](question-attachments.md).

최신 사용자 경로는 **자료 → 내부 추출/심볼/색인 → 원본 근거 → 승인 → 답변**입니다. 편집형 Knowledge UI 진입점은 제거했고 새 질문은 `includeKnowledge=false`입니다. 기존 문서/호환 API와 완료 답변은 보존하며 자동 AI 요약을 새로 구현한 것은 아닙니다. [구현·검사 대조](internal-search.md).

0.1.11 대화 중심 화면은 `App → ChatPanel / QuestionHistory → QuickAnswerPanel → AnswerJobsPanel`입니다. 자료 탭은 `SourcePanel(manageOnly)`와 기존 탐색기를 사용합니다. 모델 선택은 `ProviderConnectionPanel → modelPreference → QuickAnswerPanel`로 연결하며 DB/API는 그대로입니다. [사용 흐름·모델 대조](chat-first.md).

0.1.10 기본 질문은 `EvidenceBundlePanel → QuickAnswerPanel → AnswerJobsPanel`입니다. 명시적 전송 동의 후 기존 CAS 승인 저장과 답변 접수를 순서대로 수행합니다. API·DB v21은 그대로이며 응답 유실 재확인은 같은 요청 ID를 씁니다. 고급 이전 문맥 경로는 별도 유지합니다. [흐름·안전 경계·테스트 대조](simple-workflow.md).

0.1.7: `supervisor → forge.ai.codex-stream-home → CodexProfile`이 앱 전용 인증 위치를 연결합니다. `ProviderConnectionAdapter/ProviderLoginAdapter/CliCuratorModel/CodexAppServer`는 같은 폴더를 사용합니다. 승인 binding에도 실행 프로필을 포함해 변경 전 승인을 재사용하지 않습니다. [코드·모델·검사](codex-managed-login.md).

0.1.6의 자료 이름·분류 수정은 `SourceMetadataEditor → SourceController → SourceService → JdbcSourceStore`로 연결합니다. 기존 `pollingRevision`으로 설정 충돌을 검사하고, 수집 중 편집을 차단합니다. Source·루트 폴더·검색 큐를 같은 트랜잭션으로 반영하며 DB v21과 불변 근거를 유지합니다. [실제 코드·검사](source-metadata.md).

0.1.5의 GitHub 수집은 `stage-git.sh → resources/git → supervisor::bundled_git → --forge.git-home → GitRunner`로 앱 내부 Git을 실행합니다. Git과 HTTPS 도구는 CPU별로 빌드하며 macOS 시스템 라이브러리만 링크합니다. 전체 Git 소스·GPL·빌드 절차도 동봉합니다. Source/검색/지식 모델과 DB v21은 유지합니다. [설계·코드 대조](bundled-git.md).

0.1.4의 모델 선택은 `preferredProvider → GenerationPanel의 provider → loadChoice(provider)` 순서로 초기화합니다. Codex 설정을 Claude에 복원하지 않습니다. 브라우저 UI·회귀 검사와 원본/지식/답변 흐름은 [전체 E2E](e2e-report.md)에 대조했습니다. DB v21과 외부 전송 승인 경계는 유지합니다.

0.1.3 업데이트는 `main.rs → updates.rs → Tauri Updater`의 네이티브 경로입니다. 시작 시 또는 Mac 메뉴에서 공개 버전 정보만 조회하고, 사용자 확인 후 CPU별 서명 패키지를 받습니다. 서명·버전을 검증한 뒤 백엔드 종료를 기다려 앱만 교체합니다. 원본/지식 DB 모델(v21)과 AI 승인 경로는 바꾸지 않으며 WebView IPC 권한도 추가하지 않습니다. [업데이트 설계·배포](automatic-updates.md).

설치형 HTTP 응답의 `Referrer-Policy: same-origin`은 실제 `desktopTransport`의 same-origin 변경 요청과 호환됩니다. 외부 Origin·`Origin: null`·세션 없는 요청은 계속 차단하고, 거부 메시지는 UTF-8로 씁니다. [재현·회귀 검사](release-0.1.3-report.md).

0.1.2 연결 개선: `ProviderConnectionPanel → ProviderConnection/ProviderLogin → 프로세스 어댑터`로 인증 조회와 명시적 로그인을 분리합니다.
인증은 외부 모델 요청 승인이 아닙니다. `stage-codex.sh → Tauri resources/ai → supervisor → CliLaunchConfiguration`으로 동봉 실행 파일을 자동 연결합니다. [계약·제한](provider-connection.md).

쉽게 보기: [문서 목차](README.md) → [설계도](design-map.md) → [모델/코드 대조](modeling.md).
Mac 패키지는 공통 React/JAR와 CPU별 Tauri/Java 런타임으로 구성합니다.
`package-macos.sh`의 대상 JDK/타깃 선택 → `macos-architecture.sh`의 앱/JVM 일치 확인 → DMG 생성 순서입니다.
교차 빌드의 정적 검사와 실제 기기 실행 검증은 분리합니다. [Apple Silicon](apple-silicon.md).

`CliLaunchConfiguration → CliLaunchStore → JdbcCliLaunchStore`는 재시작 적용 설정입니다. 생성·진단·Codex 스트림은 같은 활성 명령을 사용하며 `CuratorSettings.executionBinding`이 지식/답변 승인 요청에 결합됩니다. [v21 경계](cli-launch-settings.md).

`RelationSearch → RelationCatalog → JdbcRelationCatalog`는 Lucene 결과에서 시작하는 읽기 전용 연결 탐색입니다. 심볼 이름/종류와 게시 근거 origin_key를 사용하며 AI·재분석과 분리합니다. [실제 설계](relations.md).

URL Collector는 이전 원본/확인표를 입력받아 `PublicHttpReader`의 조건부 요청으로 연결합니다. 304는 본문 재사용이며 확인표는 원본 반영과 같은 트랜잭션에 저장합니다. [v20 모델·보안 경계](url-conditional.md).

GitHub Collector는 선점 뒤 읽은 이전 원본을 입력받아 같은 commit의 clone을 생략하고 변경 blob만 읽습니다. 새 스키마/영속 Git 캐시는 추가하지 않습니다. [설계·실제 코드](git-incremental.md).

`SourcePolling → SourceStore.beginPoll → SourceService → JdbcSourceStore.publish`는 opt-in 예약 수집 경로입니다. SQLite v19의 예약·설정 revision을 보존하고 자동 작업 2개/Source별 단일 선점을 적용합니다. [실제 코드와 정책](source-polling.md).

`desktop/src-tauri`는 토큰/준비 상태/프로세스 그룹/창을 관리합니다. 백엔드 준비 전에는 로컬 시작 창만 표시합니다. 앱 데이터 위치는 고정 식별자 `dev.knowledgeforge.desktop`로 계산하며 Java는 PATH가 아닌 번들 안 절대 경로로 실행합니다. [계약](../desktop/README.md).

`ConversationPreviewService`는 읽기 전용 문맥 미리보기를 만듭니다. `ConversationRequestService`가 명시적으로 고정 저장하면 `StoredAnswerContext → AnswerContextProvider → EvidenceAnswerPreview`를 통해 기존 승인·작업 경로에 결합합니다. 완료 답변과 `ConversationCompletion`의 보관함 연결은 원자적입니다. [검증 경계](context-requests.md).

`SourceService → SnapshotPlanner → JdbcSourceStore`는 전체 수집본 중 변경 파일만 분석하고 기존 ID를 보존합니다. [증분 반영](incremental-snapshots.md). 선택적 `desktop` 모듈은 Spring 초기화 전 데이터 잠금/세션 인증을 제공하며 프런트엔드의 인증 fetch·SSE·다운로드와 연결합니다. [설치형 기반](desktop-backend.md).

그림 요약: [현재 구현과 앞으로의 설계도](design-map.md). 구현된 경로와 미구현 계획을 구분합니다.
쉬운 설명: [데이터 모델과 코드 대조](modeling.md), [작동 원리·사용법](how-it-works.md).

## 현재 구조

`SourcePanel → AnswerHistoryPanel`은 기존 작업/근거를 조회합니다. 명시적으로 여는 `ConversationShelf → ConversationService → ConversationStore → JdbcConversationStore`는 v17 보관함과 순서 연결을 관리합니다. 답변 생성/승인과 독립이며 이전 문맥을 전송하지 않습니다. [모델·경계](conversations.md).

`CliCuratorModel → CodexAppServer → CodexAppServerSession → CodexTextStream`은 전용 프로필 옵션이 켜진 Codex 답변 경로입니다. 설정·thread 검사 후 요청문을 한 번 전송합니다. 기본 exec와 지식 생성 경로는 그대로입니다. [격리·한도·한계](codex-streaming.md).

v16 `AnswerJobService`는 승인 ID 유일성이 있는 작업을 같은 트랜잭션으로 예약합니다.
가상 스레드는 전송 직전 재검증 후 DB 연결 없이 `AnswerModel → CliAnswerModel`을 호출합니다.
기존 제한된 CLI 전송 어댑터를 재사용하되 지식 생성/게시는 하지 않습니다. 결과는 인용 검사·취소/근거 확인 후 작업에만 저장합니다.
`AnswerJobEventsController`는 이 작업을 읽기 전용 SSE로 관측합니다. 근거→상태→완료 순서이며 16개 연결 한도와 연결 수명 제한을 둡니다.
`AnswerJobsPanel → observeAnswerJob → EventSource`로 실행 중 작업의 SSE를 표시합니다. 제한 재연결·범위/순서 검사·전환 시 관측 정리를 지원합니다.
Claude는 `StreamingAnswerModel → CliCuratorModel → ClaudeTextStream`으로 텍스트를 메모리에 누적하고 SSE partial로 전달합니다.
최종 JSON 검증 경로와 임시 글을 분리합니다. Codex도 opt-in 실행기가 같은 메모리/SSE 경로에 연결됩니다. 선택 문맥 다중 턴은 구현했으며 실제 서비스 스트림 재검증은 별도입니다. [Codex 경계](codex-streaming.md), [Claude 경계](partial-answer.md), [UI 연결](answer-events-ui.md).

`conversation` 모듈은 `AnswerApprovalService → AnswerApprovalStore → JdbcAnswerApprovalStore`로 요청별 승인·철회·만료를 관리합니다.
미리보기 재검증과 승인 저장은 같은 트랜잭션이며 외부 CLI는 호출하지 않습니다. v15 추가 테이블은 묶음당 현재 승인 하나입니다.
승인 저장 자체는 전송하지 않으며, 별도 v16 답변 작업이 승인을 한 번 소비합니다. [승인 모델·경계](answer-approval.md).

저장한 묶음은 `EvidenceBundleValidator`가 현재 원본/게시본/출처/도메인과 읽기 전용으로 대조합니다.
`EvidenceAnswerPreview`는 통과한 묶음만 제공자·모델에 연결된 요청문/해시로 만듭니다. LLM 호출이나 승인 기록은 없습니다.
미리보기 자체는 테이블을 추가하지 않으며 검색 색인 잠금을 잡지 않습니다. 이후 승인 테이블은 v15로 추가했습니다. [설계·API·한계](bundle-validation.md).

v14의 `evidence_bundle`은 검색 결과 중 현재 자료와 일치하는 인용문·출처를 불변 JSON으로 보관합니다.
`EvidenceBundleService`는 검색 잠금 해제 후 `EvidenceBundleWriter`의 트랜잭션을 실행합니다.
게시 교체/범위/본문 해시를 재확인하며 5개·인용문 12,000자·100개 보관 한도를 적용합니다. [구조·한계](evidence-bundles.md).

원본 검색의 `LuceneSearchIndex`는 본문 term vector offsets를 저장하고 검색어 주변과 추출 텍스트 행 범위를 반환합니다.
검색 시 원본 재수집/재분석 없이 색인을 읽습니다. [위치 표시](search-evidence.md).
v13 게시 큐 트리거와 `JdbcSearchCatalog`가 최신 게시 버전만 같은 색인에 넣습니다.
검색 후 삭제/교체된 게시본을 제외하고 근거 상태를 조회합니다. 영속 청크는 아직 없습니다. [게시 검색 모델](published-search.md).

근거 번호 통합은 `MergeEvidencePlanner`가 현재 번호를 보존하고 AI 스냅샷/행 범위를 연결합니다.
미리보기와 저장 시 동일 규칙으로 계산하며 기존 v12 근거 연결 테이블을 사용합니다. [정책](evidence-merge.md).

수동 부분 반영은 `ProposalAcceptanceService.merge`가 최종 편집 본문을 검사한 뒤
`KnowledgeService.revise`와 MANUAL 채택 이력을 한 트랜잭션으로 저장합니다.
생성 이후 사람 편집은 허용하되 최신 기대 버전·동일 근거 연결 검사를 유지합니다. [범위](proposal-merge.md).

전체 채택은 `ProposalAcceptanceService`가 처리 상태·버전·근거·인용을 검사하고,
하나의 트랜잭션에서 새 DRAFT와 v11 채택 이력을 저장합니다. 편집 충돌은 병합하지 않고 거절합니다.
[실제 모델과 제한](proposal-acceptance.md).

제안 거절·재검토는 `ProposalDecisionService`와 독립 저장 포트가 v10의 추가 전용 이력을 관리합니다.
기대 문서 버전/처리 revision을 검사하며 지식 버전이나 게시본을 수정하지 않습니다. [모델·한계](proposal-decisions.md).

AI 결과 비교는 `GenerationComparisonService`가 기존 작업/지식 저장 포트를 조회합니다.
생성 당시 버전·현재 버전·결과 v1을 한 읽기 트랜잭션으로 반환하며 저장이나 모델 호출을 하지 않습니다.
[흐름과 한계](generation-comparison.md).

단일 Spring Boot 프로세스의 Modular Monolith를 사용합니다.
`library`는 Workspace와 원본 보관함을 소유합니다. Phase 2에서 `sources`, `ingestion`,
`search`를 추가했고 Phase 3에서 `knowledge`와 CLI Curator를 추가했습니다. `conversation`은 근거 묶음·승인·답변 작업·SSE 관측·선택 문맥 이어 질문을 담당합니다. Source별 선택적 자동 동기화는 `sources`에서 구현합니다. 실제 서비스 스트림 호환성 검증과 영속 Git 캐시는 별도입니다.

```text
frontend/src
  App.tsx                 Workspace 및 원본/지식 탐색 UI
  api.ts                  HTTP 클라이언트와 응답 타입
  components/ui           shadcn/ui 스타일의 재사용 UI
backend/.../library
  domain                  Workspace, Entry 순수 Java record
  application             LibraryService, LibraryStore 포트, 유스케이스 오류
  adapter/web             REST, 문제 응답, 로컬 요청 필터
  adapter/persistence     SQLite JDBC 어댑터, 스키마 버전
backend/.../sources
  domain                  Source, CollectedSnapshot
  application             Collector/SourceStore/RemoteReader 포트, SourceService
  adapter                 로컬 폴더, 공개 HTTPS, GitHub API+Git CLI, JDBC, REST
backend/.../ingestion
  domain                  Analysis, Symbol
  application             DocumentAnalyzer/AnalysisStore 포트
  adapter                 TikaJavaAnalyzer, JdbcAnalysisStore
backend/.../search
  application             SearchIndex/SearchCatalog 포트, SearchService
  adapter                 LuceneSearchIndex, JdbcSearchCatalog
backend/.../knowledge
  domain                  Card, Version, Evidence, Citation 순수 record
  application             KnowledgeService, KnowledgeStore, CuratorService, CuratorModel 포트
  adapter                 JDBC 영속 스냅샷/버전, REST, CliCuratorModel
backend/.../conversation
  domain                  AnswerApproval, AnswerJob 순수 record
  application             승인/작업 서비스, 저장 포트, AnswerModel, AnswerOutput
  adapter                 JDBC 승인/작업, REST, CliAnswerModel
```

의존 방향은 웹 어댑터 → 애플리케이션 → 도메인/저장 포트입니다.
JDBC 어댑터가 저장 포트를 구현하고 Spring이 주입합니다.
도메인은 Spring·JDBC·HTTP를 참조하지 않습니다.
애플리케이션은 Spring의 트랜잭션 경계만 사용하며 SQL과 HTTP 객체를 알지 못합니다.

## 저장 모델과 불변 조건

- `workspace`: 식별자, 고유 이름, 생성 시간
- `app_state`: 선택 Workspace FK. 선택 변경은 SQLite에 저장됩니다.
- `entry`: Workspace FK, 부모 Entry FK, 이름, 종류, 영역, 크기, 원문 BLOB, 생성 시간
- `(workspace, area, parent, name)` 고유 인덱스로 동일 위치의 덮어쓰기를 막습니다.
- 생성 시 부모의 소유 Workspace, 영역, 폴더 여부를 유스케이스에서 확인합니다.
- ID는 서버에서 UUID로 생성합니다. 파일명은 저장 경로로 사용하지 않습니다.
- FK cascade로 Workspace/폴더의 모든 하위 자료를 같은 트랜잭션에서 삭제합니다.
- 마지막 활성 Workspace 삭제 시 가장 먼저 생성된 남은 Workspace를 선택합니다.
- 원본 탐색은 `entry.area`를 사용하며 지식은 별도 `knowledge_card/version` API와 테이블을 사용합니다.
  기존 `entries?area=KNOWLEDGE`는 지식 카드 목록 API가 아닙니다.
- 조회 목록은 BLOB을 읽지 않습니다. 텍스트 미리보기는 크기를 확인한 후 읽습니다.

제한된 파일(10 MiB)을 SQLite BLOB에 보관하여 메타데이터와 내용의 원자성을 확보합니다.
Phase 2에서도 Source당 50 MiB/500개 제한을 유지해 같은 원자성을 사용합니다.
별도 `ArtifactStore`와 콘텐츠 해시 기반 파일 저장소 전환은 대용량 지원 시 진행합니다.

SQLite는 WAL, 외래 키, 5초 busy timeout을 사용합니다. 연결 풀은 1개로
로컬 쓰기를 직렬화합니다. `PRAGMA user_version=17`로 스키마를 관리하며 새 스키마를
구버전 앱으로 열면 실패합니다. 초기 스키마 생성도 트랜잭션입니다.

## 후속 모듈과 경계

표는 목표 역할입니다. knowledge는 수동/AI 초안·버전·근거·CuratorModel이 구현되었고 사람 검토/명시적 게시가 구현되었습니다. 자동 의미 검증은 미구현입니다.

| 모듈 | 소유 데이터/역할 | 외부 연결 포트 |
|---|---|---|
| library | Workspace, 폴더, 원본 보관함, 지식 탐색 projection | LibraryStore, 후속 ArtifactStore |
| sources | Source 등록, 종류, pollingEnabled, interval, revision, 실패 상태 | Collector, GitClient, HttpFetcher |
| ingestion | 추출·정규화·청크·코드 심볼과 원본 버전 | Tika, JavaParser |
| search | Lucene/Nori 인덱스, 심볼 및 관계 검색, 다중 도메인 필터 | SearchIndex, SymbolIndex, RelationQuery |
| knowledge | 카드, Markdown, 원본 근거, 검증 상태, 버전 및 수동 편집 보호 | KnowledgeRepository, CuratorModel |
| conversation | 근거 선공개, LLM 호출, SSE, 취소 | Retrieval, LlmProvider |
| synchronization | Source별 opt-in 폴링, Git diff, 관련 지식 무효화 | Scheduler, RevisionStore |
| desktop | Tauri 2 호스트, Java 런타임과 프로세스 수명, 업데이트 | SidecarLauncher |

Collector는 실제 파일·HTTP·Git을 읽습니다. LLM의 가짜 구현은 넣지 않았습니다.
Source 식별자와 현재 원본 해시/revision을 연결합니다.
도메인은 사용자 태그/추출 결과로 관리하며 특정 업무명을 코드 상수로 사용하지 않습니다.

## 후속 데이터 흐름

1. Source 등록 → 읽기 전용 수집 → 원본 버전 보관
2. Tika/JavaParser 정규화 → 청크·심볼 → Lucene/Nori 인덱싱
3. Curator가 근거를 가진 초안을 생성 → 검증 → 새 버전 게시
4. 질문 → 기존 인덱스에서 검색 → 근거 SSE 이벤트 → 별도 LLM 답변 스트림
5. 취소 → LLM 요청과 서버 작업 취소, 클라이언트 연결 종료
6. Source별 opt-in 스케줄 또는 수동 동기화 → 변경분 → 영향 받는 지식 무효화

검색은 질문마다 재수집·재분석하지 않습니다. 검색과 답변 생성은 별도 유스케이스로
분리합니다. `draft / verified / published / stale`, 원본 revision, 수동 편집 여부를
후속 지식 버전 모델에 포함합니다. 원본 없는 추론은 사실로 게시하지 않습니다.
사람이 편집한 문서는 갱신 제안으로 남기고 직접 덮어쓰지 않습니다.

## 로컬 보안 경계

서버는 127.0.0.1에 바인딩하고 허용 Host/Origin을 검사합니다.
변경 요청은 `X-Forge-Client: 1`이 필요하며 CORS 허용은 하지 않습니다.
Vite 개발 서버가 동일 출처 프록시를 제공합니다.
이 헤더는 인증 비밀이 아니라 외부 웹페이지의 단순 CSRF 요청을 막는 장치입니다.
로컬 프로세스/같은 OS 계정에 대한 인증 경계는 제공하지 않습니다.

원본은 `application/octet-stream` 첨부 파일로 내려보내며 `nosniff`를 설정합니다.
미리보기는 React 텍스트로 렌더링하므로 HTML/스크립트를 실행하지 않습니다.
SQLite 이름 충돌은 409이며 업로드로 덮어쓰지 않습니다.

CLI AI 호출은 미리보기와 Provider/요청 모델/Workspace/버전/내용 SHA-256에 묶인 승인 후 실행합니다.
`CuratorSettings`는 시작 기본값과 건별 선택을 정규화합니다. 미리보기에서 해석한 모델을 승인하고,
접수한 작업에 저장합니다. worker는 저장된 모델을 `CuratorModel` 포트와 CLI 어댑터에 명시적으로 전달합니다.
`CliDiagnostics` 포트는 사용자 요청 시 로컬 버전만 검사하며 생성/인증/원격 연결과 분리합니다.
요청 모델·승인 digest는 생성된 불변 초안에 기록합니다. CLI 관측은 작업 이력에 보존하지만 실제 실행 모델 ID와 정확한 바이너리 증명은 후속 작업입니다.
계약과 한계는 [건별 모델 선택](model-selection.md), [설치 진단](cli-settings.md)을 따릅니다.
사용자 로그인은 CLI가 처리하고 앱은 인증 파일을 읽지 않습니다. [CLI 경계/제약](cli-ai.md).
GitHub Actions는 변경하지 않습니다.
Phase 6에서 데스크톱 세션 토큰, sidecar origin, 인증서 서명·공증을 검증해야 합니다.

## Phase 2 동기화와 인덱스 일관성

`source`는 기본 polling OFF이며 상태는 NEW → SYNCING → READY/ERROR입니다.
원격 I/O와 Source 전체 추출은 DB 트랜잭션 밖에서 수행합니다.
성공한 스냅샷만 한 트랜잭션으로 원본/분석/심볼/revision에 게시합니다.
실패는 error만 기록해 이전 반영 버전을 유지합니다. 변경 없으면 재분석·파일 ID 교체를 생략합니다.
변경된 스냅샷은 전체 교체하므로 Git 증분·과거 버전 보관은 별도 Phase 5 작업입니다.
동기화 중 삭제된 Source를 다시 생성하지 않으며, 재시작 시 남아 있는 SYNCING은 ERROR로 복구합니다.

`extracted_document`, `symbol`은 SQLite에 저장합니다. `search_outbox`는 DB 변경과 같은
트랜잭션으로 인덱스 갱신 요청을 남깁니다. Lucene commit 후에만 큐를 확인 처리합니다.
삭제는 SQLite trigger로 큐에 남겨 cascade도 인덱스에 반영합니다.
API 완료 시 갱신하며 2초 주기의 로컬 큐 소비로 실패/중단을 복구합니다.
이 주기는 Source 폴링이 아니며 외부 통신을 하지 않습니다.
앱 시작 시 저장된 분석 결과로 인덱스를 재구성하지만 원본을 재추출하지 않습니다.
검색 요청은 현재 인덱스만 조회하며 삭제된 DB 항목은 결과에서 다시 제외합니다.

Lucene 검색은 Nori 토큰별 AND, 파일명/본문/심볼 중 OR입니다.
Workspace는 필수 필터이고 선택 도메인들은 OR 필터입니다.
최대 30건과 원문 스니펫을 반환하며 LLM은 호출하지 않습니다.

HTTPS 수집은 443만 허용하고 DNS resolver 단계에서 공용 주소만 연결합니다.
각 리다이렉트에 URL 검사를 다시 적용하고 자동 리다이렉트를 끕니다.
응답 10 MiB, 요청당 25초, 리다이렉트 3회 제한입니다.
GitHub는 REST API SHA와 Git HEAD가 일치하는지 확인합니다. Git은 checkout 없이 blob만 읽고,
global/system config·credential helper·hooks·file/ext protocol·HTTP redirect를 차단합니다.
Git 명령은 인자 배열로 실행하며 명령당 60초/임시 디스크 100 MiB 제한입니다.
로컬 파일 트리는 읽기 전용이고 심볼릭 링크/특수 파일은 실패 처리합니다.
외부에서 동시에 폴더 구조를 바꾸는 상황의 완전한 파일시스템 스냅샷 격리는 제공하지 않습니다.

## 기술 버전

Java 21, Spring Boot 4.1.1, React 19.1.1, Vite 7.3.7, Vitest 4.1.12, Tailwind 4.1.13,
SQLite JDBC 3.50.3.0. npm은 lockfile, Maven은 parent/dependency 버전으로 고정합니다.
Phase 2는 Tika 3.2.3, JavaParser 3.27.1, Lucene/Nori 10.3.1을 사용합니다.
API는 [Lucene IndexWriter](https://lucene.apache.org/core/10_3_1/core/org/apache/lucene/index/IndexWriter.html)와
[Tika API](https://tika.apache.org/3.2.3/api/org/apache/tika/Tika.html)를 확인했습니다.
Spring Boot 4.1.1의 Java 21 호환성은
[공식 시스템 요구사항](https://docs.spring.io/spring-boot/system-requirements.html)에서 확인했습니다.

## Phase 3 기반: 독립 지식과 불변 근거

`knowledge_card`는 Workspace를 소유 범위로 하며 `latest_version`을 가집니다.
`knowledge_version(card_id,number)`은 제목/Markdown/상태/수동 편집 여부를 저장합니다.
기존 버전의 본문을 UPDATE하는 API는 없고 `expectedVersion` 비교 후 새 버전을 추가합니다.
동시 편집은 한 요청만 성공하고 오래된 요청은 409입니다. 현재 생성 상태는 오직 DRAFT이며
VERIFIED/PUBLISHED 스키마 값은 사용하지 않습니다. v7 검토/게시 이력은 별도 테이블과 PublicationService/PublicationStore로 관리합니다. [불변 조건과 흐름](publication.md).

`evidence_snapshot`은 근거 캡처 시 원본 BLOB, 추출 텍스트, SHA-256 두 개,
Source ID/이름/종류/등록 위치/당시 revision/상대 경로, 당시 entry ID를 보존합니다.
Source와 entry에 FK를 걸지 않아 Source의 전체 스냅샷 교체 또는 삭제와 독립적으로 유지됩니다.
Workspace FK는 cascade입니다. 전체 Source 이력이 아니라 **지식에서 인용한 원본만** 보관합니다.
같은 Workspace·origin·revision·원본 해시·텍스트 해시 조합은 재사용합니다.
`knowledge_evidence`는 버전별 스냅샷 및 추출 텍스트 행 범위를 연결합니다.
현재 일반 문서의 물리 페이지/원문 행 위치 매핑은 없으므로 UI/API에서 추출 행임을 명시합니다.

초안 생성과 근거 캡처는 같은 SQLite 트랜잭션입니다. 원본/분석의 일관된 복사본을 얻으며
잘못된 근거가 포함되면 캡처·버전 변경 모두 롤백합니다. 한 초안에 최대 10개/50 MiB입니다.
버전 개수·전체 DB 용량 제한 및 보존 기한은 아직 없으므로 장기 사용 시 저장소가 커질 수 있습니다.
지식 삭제는 그 버전들을 삭제하고, 다른 지식이 참조하지 않는 스냅샷만 정리합니다.
원본만 삭제할 때는 지식 근거 복사본이 남으므로 민감 자료 삭제 시 이 보존 정책에 주의해야 합니다.

최신성은 조회 시 마지막 로컬 원본/추출 해시와 비교합니다. Source의 다른 파일만 바뀌어
entry ID가 교체되어도 같은 Source+경로+해시라면 CURRENT입니다. 파일 내용 변경은 STALE,
파일/Source 삭제는 MISSING입니다. 같은 경로의 다른 Source 등록은 기존 출처로 간주하지 않습니다.
이는 원격 폴링·자동 지식 무효화/재생성이나 사실 검증이 아닙니다.
초안은 Lucene 검색에 아직 포함하지 않습니다. Markdown은 `KnowledgeMarkdown`에서
react-markdown/remark-gfm으로 React 요소에 표시하며 원문은 별도로 보존합니다.
raw HTML 파싱·실행 없이 링크는 비활성 텍스트, 이미지는 대체 설명으로 표시합니다.
API·불변 버전·승인 digest·검토/게시 상태는 변경하지 않습니다. [표시 설계](markdown-rendering.md).

## 독립 지식 폴더 (v8 도입, 현재 v17)

v9는 폴더 revision으로 이름/부모 변경 충돌을 막습니다. 변경 후 전체 트리의 순환과 깊이를
검사한 뒤 같은 트랜잭션에서 저장합니다. 문서·카드 배치 revision은 별개입니다. [설계·검증](folder-update.md).

`OrganizationService` → `OrganizationStore` → `JdbcOrganizationStore`가 폴더와 카드 배치를 관리합니다.
`knowledge_folder`는 원본 entry와 독립이며 `knowledge_placement`의 위치 revision은 문서 버전과 분리합니다.
카드 이동은 제목/Markdown/근거/검토/게시본을 바꾸지 않습니다. 기존 목록 API는 전체 카드를 반환하고
UI가 catalog로 필터링합니다. 빈 폴더만 삭제하며 Workspace 삭제는 구조/배치를 정리합니다.
마이그레이션, CAS, 삭제 정책과 한계는 [폴더 설계](knowledge-folders.md)를 따릅니다.

## 근거의 현재 원본 탐색

지식 근거의 현재 원본 탐색은 `KnowledgeService.locateEvidence`가 선택한 카드/버전의 근거 소속을
확인한 뒤 `KnowledgeStore.currentMaterial`과 `LibraryStore`로 파일·부모 경로를 읽습니다.
최신성 판정과 탐색은 같은 Source ID+경로/업로드 ID 해석을 공유하며 스냅샷은 수정하지 않습니다.
UI는 폴더 목록을 재조회하고 현재 ID가 있을 때만 미리보기를 선택합니다. [탐색 설계](evidence-navigation.md).

## AI 생성 작업 (v6, 작업 상태는 v4에서 도입)

`generation_job(workspace_id,id)`는 승인 digest/Provider/기반 카드·버전/상태/결과 카드/시각을 저장합니다.
v5에서 요청 모델도 저장합니다. 기존 작업의 모델은 null(미기록), 새 작업의 빈 문자열은 CLI 기본 선택입니다.
v6은 CLI 실행 전 관측 버전·시각·상태를 추가합니다. worker가 DB 트랜잭션 밖에서 `CliDiagnostics`를 호출하고
관측을 먼저 저장한 후 생성합니다. 진단 실패는 기록 후 계속하지만 기록 저장 실패는 생성 전에 중단합니다.
취소와 기록 저장을 잠금으로 직렬화하며 기록된 값은 작업 실패·취소·재시작 후에도 보존합니다.
관측은 생성 프로세스 자체의 바이너리 증명이 아닙니다. [설계와 경계](cli-observation.md).
브라우저의 선택 저장은 localStorage에만 적용하고 전송 승인·문서·자격 증명은 저장하지 않습니다.
`GenerationJobService`는 짧은 트랜잭션으로 요청을 등록하고 가상 스레드에서 CLI를 실행합니다.
CLI I/O 동안 DB 트랜잭션을 점유하지 않습니다. 결과 카드와 SUCCEEDED는 같은 트랜잭션으로 커밋합니다.
취소와 결과 커밋은 같은 잠금으로 직렬화하며 취소가 먼저면 늦은 출력을 폐기합니다.
같은 요청 ID는 다시 호출하지 않습니다. 재시작 시 미완료 작업은 INTERRUPTED로 복구하고 자동 실행하지 않습니다.
Workspace 삭제는 작업 이력도 제거하며 실행 중 worker는 로컬 감시로 중단합니다.
전체 계약/상태 흐름/보안 경계/한계는 [AI 생성 작업](generation-jobs.md)을 따릅니다.
