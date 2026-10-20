# Phase 완료 기준 및 남은 작업

## Phase 1 — 이번 구현

- Java 21 / Spring Boot 4.1.x / React 빌드 성공
- Workspace 생성·삭제·전환, 서버 재시작 후 선택/데이터 보존
- 폴더 하위 탐색, 경로 복귀, 원본 파일 업로드·미리보기·다운로드
- Workspace 격리, 중복 이름 거부, 삭제 cascade, 크기/경로 검증
- 원본과 지식 영역 독립, 미구현 기능을 완료된 것으로 표시하지 않음
- 자동 API/UI 테스트 및 실제 브라우저 사용자 흐름
- 실행·아키텍처·API 문서, 실제 측정 보고서

실행 증거와 결과는 `test-report.md`, `performance.json`에 기록합니다.

## Phase 2

로컬 폴더/URL/GitHub read-only Collector, Source 모델, Git CLI·REST API,
Tika·JavaParser, Lucene 10·Nori 및 심볼 인덱스를 구현합니다.
SSRF, 리다이렉트, 심볼릭 링크, 대용량 파일, 중단/재시도, 자격 증명 보관을 검증합니다.
가상 Java 프로젝트·문서·로컬 HTTP fixture로 수집 및 한글 검색 결과를 검증합니다.
수집 후 원본 버전과 해시를 보존하고, 질문은 기존 인덱스만 이용하도록 합니다.

## Phase 3

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
- Source 등록·자동 수집·분석·검색·AI·폴링 미구현
- 원본 폴더 생성/삭제만 지원하며 이름 변경·이동은 미지원
- OS 사용자 간 인증, 데이터 암호화, 데스크톱 session token은 미구현
- SQLite cascade의 매우 깊은 트리는 별도 제한/배치 삭제 설계가 필요
- Phase 1 성능 값은 작은 가상 데이터셋 결과로 대규모 처리 성능을 보장하지 않음
