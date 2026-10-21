# 아키텍처

그림 요약: [현재 구현과 앞으로의 설계도](design-map.md). 구현된 경로와 미구현 계획을 구분합니다.
쉬운 설명: [데이터 모델과 코드 대조](modeling.md), [작동 원리·사용법](how-it-works.md).

## 현재 구조

AI 결과 비교는 `GenerationComparisonService`가 기존 작업/지식 저장 포트를 조회합니다.
생성 당시 버전·현재 버전·결과 v1을 한 읽기 트랜잭션으로 반환하며 저장이나 모델 호출을 하지 않습니다.
[흐름과 한계](generation-comparison.md).

단일 Spring Boot 프로세스의 Modular Monolith를 사용합니다.
`library`는 Workspace와 원본 보관함을 소유합니다. Phase 2에서 `sources`, `ingestion`,
`search`를 추가했고 Phase 3에서 `knowledge`와 CLI Curator를 추가했습니다. 대화·자동 동기화 모듈은 후속 범위입니다.

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
로컬 쓰기를 직렬화합니다. `PRAGMA user_version=9`로 스키마를 관리하며 새 스키마를
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

## 독립 지식 폴더 (v8 도입, 현재 v9)

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
