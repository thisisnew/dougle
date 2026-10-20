# 아키텍처

## Phase 1 구조

단일 Spring Boot 프로세스의 Modular Monolith를 사용합니다.
지금 구현된 bounded context는 `library`이며 Workspace와 원본 보관함을 소유합니다.
나머지 모듈은 아래 계약을 기준으로 후속 Phase에서 실제 기능과 함께 추가합니다.

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
- 원본과 지식은 `area`로 분리합니다. 현재 쓰기 API는 원본만 지원합니다.
- 조회 목록은 BLOB을 읽지 않습니다. 텍스트 미리보기는 크기를 확인한 후 읽습니다.

Phase 1의 제한된 파일(10 MiB)을 SQLite BLOB에 보관하여 메타데이터와 내용의
원자성을 확보했습니다. Phase 2의 대용량 수집에서는 `ArtifactStore` 포트와
콘텐츠 해시 기반 파일 저장소를 추가하고 DB/BLOB 마이그레이션 및 복구를 테스트합니다.

SQLite는 WAL, 외래 키, 5초 busy timeout을 사용합니다. 연결 풀은 1개로
로컬 쓰기를 직렬화합니다. `PRAGMA user_version=1`로 스키마를 관리하며 새 스키마를
구버전 앱으로 열면 실패합니다. 초기 스키마 생성도 트랜잭션입니다.

## 후속 모듈과 경계

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

현재 실행 코드에 미구현 Collector나 LLM의 가짜 구현을 넣지 않았습니다.
Phase 2부터 Source 식별자를 원본 버전에 연결합니다.
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

현재 외부 LLM 호출은 없습니다. 후속 외부 Provider는 선택 및 전송 승인 없이
사용할 수 없도록 Provider 정책 포트를 통과하게 합니다. GitHub Actions는 변경하지 않습니다.
Phase 6에서 데스크톱 세션 토큰, sidecar origin, 인증서 서명·공증을 검증해야 합니다.

## 기술 버전

Java 21, Spring Boot 4.1.1, React 19.1.1, Vite 7.1.7, Tailwind 4.1.13,
SQLite JDBC 3.50.3.0. npm은 lockfile, Maven은 parent/dependency 버전으로 고정합니다.
Spring Boot 4.1.1의 Java 21 호환성은
[공식 시스템 요구사항](https://docs.spring.io/spring-boot/system-requirements.html)에서 확인했습니다.
