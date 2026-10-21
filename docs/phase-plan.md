# Phase 완료 기준 및 남은 작업

실행 가능한 작업 단위와 상세 인수 조건은 [남은 개발 체크리스트](backlog.md)를 따릅니다.
새 PC의 실행 절차와 다음 에이전트용 요청은 [HANDOFF](../HANDOFF.md)에 있습니다.

## Phase 1 — 완료

- Java 21 / Spring Boot 4.1.x / React 빌드 성공
- Workspace 생성·삭제·전환, 서버 재시작 후 선택/데이터 보존
- 폴더 하위 탐색, 경로 복귀, 원본 파일 업로드·미리보기·다운로드
- Workspace 격리, 중복 이름 거부, 삭제 cascade, 크기/경로 검증
- 원본과 지식 영역 독립, 미구현 기능을 완료된 것으로 표시하지 않음
- 자동 API/UI 테스트 및 실제 브라우저 사용자 흐름
- 실행·아키텍처·API 문서, 실제 측정 보고서

실행 증거와 결과는 `test-report.md`, `performance.json`에 기록합니다.

## Phase 2 — 기본 수집·분석·검색 구현

로컬 폴더/URL/GitHub read-only Collector, Source 모델, Git CLI·REST API,
Tika·JavaParser, Lucene 10·Nori 및 심볼 인덱스를 구현합니다.
SSRF, 리다이렉트, 심볼릭 링크, 대용량 파일, 중단/재시도, 자격 증명 보관을 검증합니다.
가상 Java 프로젝트·문서·로컬 HTTP fixture로 수집 및 한글 검색 결과를 검증합니다.
수집 후 원본 버전과 해시를 보존하고, 질문은 기존 인덱스만 이용하도록 합니다.

현재 로컬 폴더/공개 HTTPS/공개 GitHub 기본 브랜치의 수동 수집, 원본 해시·마지막 revision,
Tika 문서 추출, JavaParser 심볼, Lucene/Nori 다중 도메인 검색이 구현되어 있습니다.
실패 원자성·중복 동기화·읽기 전용 Git·심볼릭 링크·사설 주소·리다이렉트와
스키마 업그레이드를 테스트합니다. 실제 결과는 `phase2-report.md`에 기록합니다.

운영 확장으로 남은 범위: 비공개 GitHub 자격 증명 보관, 사용자가 누르는 수집 취소,
격리 프로세스 파서, 대용량 ArtifactStore, 세밀한 재시도 정책.
현재 수집은 제한된 동기 요청이며 실패 후 수동 재시도할 수 있습니다.

## Phase 3 — P3-1 완료, P3-2부터 남음

수동 지식 초안·불변 버전·근거 스냅샷·최신성 조회·편집 충돌 보호를 UI/API로 구현했습니다.
v3 마이그레이션과 원본/지식 독립 삭제를 검증했습니다. [검증 기록](phase3-foundation-report.md).
아직 모든 초안은 DRAFT이며 AI 생성·검증·게시가 완료된 것은 아닙니다.

Curator, Knowledge Card, Markdown 버전, 출처 위치·revision 연결,
초안 검증 후 게시를 구현합니다.
근거 없는 문장 게시 차단, 수동 편집 보호, 검증 실패 시 이전 게시 버전 유지가 완료 기준입니다.

## Phase 4

검색과 LLM 생성 유스케이스를 분리합니다.
다중 도메인 검색, 근거 우선 SSE, Ollama, 중단, Provider 확장 및 외부 전송 승인을 구현합니다.
SSE 이벤트 순서, 취소의 서버 자원 해제, 검색 실패/모델 미실행, 승인 없는 외부 전송 차단을 검증합니다.

## Phase 5

Source별 기본 OFF 폴링과 주기, 수동 동기화, Git diff, 마지막 반영 revision,
연관 지식 stale 처리, 새 버전 검증·게시를 구현합니다.
OFF일 때 호출 없음, 변경 없는 재동기화의 멱등성, 변경/삭제/이름 변경 전파,
수동 편집 보호 및 crash recovery를 검증합니다. 저장소 Workflow는 수정하지 않습니다.

## Phase 6

Tauri 2 + Java 21 런타임 sidecar, macOS .app/.dmg, 프로세스 수명과 데이터 경로
마이그레이션, 통합 테스트, 설치/사용 설명서를 완성합니다.
Intel/Apple Silicon 타깃 빌드, 설치·종료·재시작, 업그레이드·백업 복원을 검증합니다.
서명·공증에는 사용자의 Apple 배포 자격이 필요합니다. Windows는 후속 지원입니다.

## 현재 한계

- 대규모 디렉터리의 페이지네이션, 트리 가상화, 파일명 정규화/Unicode case folding 없음
- 파일 최대 10 MiB, 미리보기는 1 MiB UTF-8만 지원
- 단일 SQLite 연결, BLOB 저장이므로 대량 수집 전 저장소 확장 필요
- 공개 Source 등록·수동 수집·문서/Java 분석·검색 구현, AI·자동 폴링 미구현
- Source당 500개/50 MiB, 경로 16단계, 추출 텍스트 500,000자 제한
- Source는 현재 스냅샷/마지막 revision만 보관. 지식이 인용한 원본은 별도 보존하지만 전체 원본 이력은 미지원
- 원본 폴더 생성/삭제만 지원하며 이름 변경·이동은 미지원
- OS 사용자 간 인증, 데이터 암호화, 데스크톱 session token은 미구현
- SQLite cascade의 매우 깊은 트리는 별도 제한/배치 삭제 설계가 필요
- Phase 1 성능 값은 작은 가상 데이터셋 결과로 대규모 처리 성능을 보장하지 않음
