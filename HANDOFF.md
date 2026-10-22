# Knowledge Forge — 구현 현황 및 다음 작업

추가: [관련 자료 탐색](docs/relations.md). 저장된 선언 심볼·출처 위치로 원본/최신 게시본을 연결합니다. 의미/함수 호출 그래프나 관계 결과의 자동 AI 근거 편입은 아닙니다. 다음 핵심은 설치형 CLI 설정 UX·최신 Mac 설치 파일·전체 회귀입니다.

최신 스키마는 **v20**입니다. [URL 조건부 재수집](docs/url-conditional.md)을 추가했습니다. Git/URL 변경분 수집 이후 다음 핵심은 관계 검색·설치형 CLI 설정과 전체 설치 회귀입니다. 공개 DMG v0.1.0-preview.1은 v18 빌드이므로 이 변경을 포함하지 않습니다.

후속 추가: [GitHub 변경 blob 수집](docs/git-incremental.md). 같은 SHA는 clone 생략, 다른 SHA는 partial clone tree와 저장 원본을 비교합니다. 가상 로컬 partial clone에서 변경하지 않은 blob이 실제로 missing 상태로 남는 것을 확인했습니다. URL 조건부 요청도 추가했으며 다음은 관계 검색·설치형 CLI 설정입니다.

v19에서 추가: [Source별 자동 갱신](docs/source-polling.md) 구현. 기본 OFF, 60~86400초, 최대 자동 작업 2개, 실패 백오프, 재시작 보존. Git/URL 변경분 수집도 추가했고 설치형 CLI 설정 UX가 남습니다. v0.1.0-preview.1 DMG는 v18 기준이며 현재 소스와 구분합니다.

검증: 최신 백엔드 342 통과 + 실제 AI 1 skip, UI 192 통과. v20 JAR 전체/관계 API smoke와 실제 예약 60초/재시작 smoke도 통과했습니다. [Intel Mac 공개 릴리스](https://github.com/thisisnew/knowledge-forge/releases/tag/v0.1.0-preview.1)는 v18 빌드이며 공증되지 않았습니다.

연속 개발 지시: 사용자는 매 기능마다 멈추지 말고 완성까지 진행하도록 요청했습니다. 진행·검증·문서·푸시를 이어가며 실제 외부 AI 전송과 배포 자격 증명 등 명시 승인이 필요한 경우만 확인합니다.

최신 추가: [문맥 고정·승인·실행](docs/context-requests.md), [변경 파일만 분석](docs/incremental-snapshots.md), [설치형 인증 기반](docs/desktop-backend.md). 현재는 SQLite v20이며 네이티브 Intel 설치 빌드와 Source 선택적 폴링을 구현했습니다.

설치형 추가: Intel Mac DMG/Java 동봉·설치 복사본의 네이티브 UI 검증 통과. [설치·검증 보고서](docs/desktop-report.md). 공증/Apple Silicon/실제 AI 호환성 검증은 아닙니다. 다음은 Source 선택적 폴링·조건부 수집과 설치형 CLI 설정 UX입니다.

> 최신 화면: [대화 보관함](docs/conversations.md). 완료 답변을 순서대로 정리하고 당시 근거와 답변을 읽습니다. 생성/추가/삭제는 로컬 DB 작업이며 새 AI 전송·이전 문맥 재전송은 없습니다. SQLite v17.

> 최신 개발: [전용 Codex 스트리밍 실행기](docs/codex-streaming.md)를 답변 작업에 연결했습니다. 기본값은 기존 완료 답변이며 전용 로그인 폴더 지정 시에만 켭니다. 가상 CLI 검증과 실제 서비스 검증을 구분합니다.

> SQLite v18. [Claude 생성 중 임시 출력](docs/partial-answer.md)과 선택적 Codex 임시 출력을 지원하며 검사된 최종 답변만 저장합니다. 선택한 문맥의 다중 턴 경로는 구현했고 실제 서비스 스트림 검증은 남았습니다.

기록일: 2026-10-22 (Asia/Seoul)

Phase 2 기준 커밋은 `d4cd1e8`이며, 이후 Phase 3 P3-1 구현과 검증 기록을 추가했습니다.
전체 미완료 체크리스트와 기능별 인수 조건은 [남은 작업](docs/backlog.md)에 있습니다.
현재/예정 구조를 그림으로 보려면 [한눈에 보는 설계도](docs/design-map.md)를 먼저 읽으세요.

## 현재 상태

사용자는 **Mac 설치형이 준비된 뒤 체험**하기로 했습니다. [준비 기준](docs/desktop-preview.md)을 따릅니다.
쉬운 문서는 [작동 원리](docs/how-it-works.md), [모델링·실제 코드 대조](docs/modeling.md)입니다.
최신 개발 도구는 Vite 7.3.7 / Vitest 4.1.12이며 npm audit 경고 0개입니다. [검증](docs/toolchain-report.md).

**Phase 1·2와 Phase 3 P3-1(지식/근거 영속 모델 및 수동 초안 UI/API)을 구현했습니다.**
Java 21 / Spring Boot 4.1.1 / React / TypeScript / Vite / Tailwind / SQLite JDBC를
사용하는 실제 실행 가능한 로컬 웹 앱입니다. 최종 배포 형태는 Tauri 2 데스크톱 앱입니다.

현재 Source 수동 수집, 문서·Java 분석, 로컬 검색, 근거 연결 수동 지식 초안·버전을 사용할 수 있습니다.
로그인된 Codex/Claude Code CLI로 승인 기반 별도 AI 초안·근거 기반 질문·문맥 선택·SSE 답변을 지원합니다. Source별 선택적 자동 폴링도 지원합니다.
이전 지식 편집 개선은 [근거 번호 통합](docs/evidence-merge.md)입니다. 현재 번호를 유지하고 AI 근거를 뒤에 연결한 뒤 수동 부분 반영할 수 있습니다. 모든 근거가 CURRENT여야 하며 이전 버전·게시본은 보존합니다. 오래된 근거를 새 원본으로 바꾸는 재연결은 아직 없습니다. [폴더 이름·위치 변경](docs/folder-update.md), [안전한 Markdown 표시](docs/markdown-rendering.md), [독립 지식 폴더·카드 배치](docs/knowledge-folders.md), [현재 원본 탐색](docs/evidence-navigation.md)도 구현되어 있습니다. 이전 변경은 [사람 검토와 게시](docs/publication.md), [실행 전 CLI 관측 이력](docs/cli-observation.md), [건별 모델 선택](docs/model-selection.md)을 확인하세요.
사용자 요청으로 기본 AI 방향을 Ollama에서 로그인된 CLI로 변경했습니다. 이 결정이 이전 계획보다 우선합니다.
GitHub Actions Workflow도 생성하거나 수정하지 않았습니다.

### 완료

- Workspace 생성·삭제·전환, 활성 Workspace 영속 저장
- 원본 폴더 생성·계층 탐색·상위 경로 이동
- 10 MiB 이하 파일 업로드, 원본 바이트 다운로드, 폴더/자료 삭제
- 1 MiB 이하 UTF-8 텍스트 미리보기, HTML 실행 방지
- 원본과 지식 영역 분리, 수동/AI 초안 구별, 사람 검토·명시적 게시와 자동 의미 검증의 구분
- SQLite FK cascade / WAL / 스키마 버전 20 / 중복 이름 보호
- Workspace 간 자료 접근 격리, 로컬 Host/Origin/변경 헤더 검사
- React 비동기 취소, 오류 표시, 삭제 확인 대화상자
- UI를 포함한 실행 JAR 빌드, Maven Wrapper, 개발·빌드·실행 스크립트
- 최신 전체 빌드: 백엔드 316개 통과 + 실제 CLI opt-in 1개 skip / 프런트엔드 185개 통과. [문맥 실행 검증](docs/context-requests.md). 가상 CLI 검증이며 실제 서비스 생성 성공과 구분합니다.
- 실제 JAR 재시작 후 원본·선택 복원, 삭제 영속성, HTTP 성능 측정
- Source 등록·삭제·수동 동기화: 로컬 폴더 / 공개 HTTPS URL / 공개 GitHub 기본 브랜치
- 원본 해시·마지막 revision·오류·반영 시각, 실패 시 이전 스냅샷 보존
- Tika HTML/PDF/일반 문서 추출, JavaParser 심볼·행 번호와 원문 보관
- Lucene 10.3.1 + Nori 검색, 도메인 다중 선택, Workspace 검색 격리
- 원본 검색의 단어 주변 미리보기·추출 텍스트 행 범위·본문/이름·심볼 일치 구분 ([한계](docs/search-evidence.md))
- 원본·게시 지식 통합 검색, 초안 제외, 게시 버전 고정 열람, 복수 근거 Source 도메인 및 CURRENT/STALE/MISSING 표시 ([정책](docs/published-search.md))
- 답변용 근거 묶음: 본문 해시/게시/도메인 재대조, 불변 인용·출처 보관, 개수/문자/보관 한도와 독립 삭제. 외부 AI 전송 없음 ([정책](docs/evidence-bundles.md))
- 저장된 근거 재검증·제공자/모델별 답변 요청 미리보기, 변경/삭제/범위 변경 차단. 검사 결과는 로컬 상태이며 승인이나 실제 호출이 아님 ([정책](docs/bundle-validation.md))
- 답변 요청별 10분 승인·철회·만료·오래된 화면 충돌 보호와 v15 보존 마이그레이션 ([정책](docs/answer-approval.md))
- v16 승인 일회 소비·답변 작업·CLI 호출·인용 검사·중단·재시작 INTERRUPTED 복구. UI는 완료 답변 표시. fake CLI로 검증 ([정책](docs/answer-jobs.md))
- 근거 우선 작업 관측 SSE API/UI와 Claude·선택적 Codex 미검증 임시 출력: 재연결로 AI를 재호출하지 않음 ([Codex 계약](docs/codex-streaming.md))
- 영속 분석 결과와 SQLite 인덱스 갱신 큐, 시작 시 저장된 분석 결과로 인덱스 복원
- SQLite 스키마 1→2 마이그레이션과 원본·활성 Workspace 보존
- Source 관리 파일 변경 방지, 사설 주소·리다이렉트·심볼릭 링크·용량 제한
- 지식 수동 초안 생성·독립 삭제, 불변 버전, expectedVersion 편집 충돌 409
- 당시 원본 바이트/추출 텍스트/해시/revision 스냅샷, 원본 삭제 후에도 근거 보존
- 근거 CURRENT/STALE/MISSING 조회, v1/v2→v3 마이그레이션
- CLI 전송 미리보기·건별 승인, 별도 AI DRAFT, 출력 검사·타임아웃·기존 수동 문서 보호
- 실제 Codex 생성 성공. Claude 실제 생성은 인증 오류로 미검증: `docs/cli-ai.md`
- 영속 AI 작업 상태·취소·멱등 요청·재시작 INTERRUPTED 복구, 늦은 출력 폐기, 결과 카드/성공 상태 원자적 저장
- v3→v4 마이그레이션, Workspace별 최근 작업 목록과 재전송/중단 UI
- 서버 시작 설정으로 모델 고정, 모델 포함 승인 digest, 초안 요청 모델/digest 기록, 명시적 CLI 버전 진단
- 건별 모델 선택·브라우저별 선택 저장(승인 제외), 작업별 요청 모델 영속 이력, v4→v5 보존 마이그레이션
- 실행 전 CLI 버전 관측·시각·실패 상태 보존, 관측 중 취소 보호, v5→v6 보존 마이그레이션
- 사람 검토 기록→별도 게시, 근거 변경/삭제 차단, 이전 게시본 유지, 게시 충돌 보호, v6→v7 마이그레이션
- 지식 버전의 근거에서 현재 원본 폴더/미리보기로 이동, 동기화 ID 교체 대응, STALE 경고, 삭제 시 복사본 보존
- 독립 지식 폴더 생성·계층 탐색·빈 폴더 삭제, 카드 이동 CAS, 문서/게시 이력 보존, v8 마이그레이션
- Markdown/GFM 미리보기·원문 열람, HTML 실행·링크 이동·이미지 로딩 차단, 편집/이전 버전 보존

### 미구현

| Phase | 작업 | 상태 |
|---|---|---|
| 2 확장 | 비공개 저장소 자격 증명, 대용량 ArtifactStore, 작업 취소·격리 파서 | 남음 |
| 3 | 초안/승인/작업/모델 선택, 사람 검토/게시, 폴더·카드 배치, Markdown 표시 구현. 자동 의미 검증·실제 실행 메타데이터·AI 갱신 제안은 남음 | 진행 중 |
| 4 | 통합 검색·승인·SSE·문맥 고정 다중 턴 구현. 관계 검색과 실제 서비스 검증 남음 | 진행 중 |
| 5 | Source별 opt-in 폴링, Git 증분 갱신, 지식 무효화·버전·수동 편집 보호 | 미착수 |
| 6 | Tauri 2, Java 런타임 포함 Mac 설치 앱, 통합 테스트, 설치 설명서 | 미착수 |

현재 `.app`/`.dmg`는 없습니다. Windows 설치형 지원도 후속 작업입니다.

## 다른 PC에서 시작하기

저장소를 clone한 뒤 Java 21과 Node.js 22.12 이상 또는 24, Git CLI를 준비합니다.
로컬 DB, 의존성 캐시, `node_modules`, 실행 JAR는 Git에 포함되지 않습니다.
처음에는 의존성 다운로드가 필요하며 앱 데이터는 새로 생성됩니다.

### macOS / Bash

```bash
git clone https://github.com/thisisnew/knowledge-forge.git
cd knowledge-forge
java -version
node --version
git --version
bash scripts/build.sh
node scripts/smoke.mjs
bash scripts/run.sh
```

이미 clone했다면 프로젝트에서 `git status`로 미커밋 작업을 확인한 뒤
`git pull --ff-only origin main`으로 받습니다. 로컬 변경을 강제로 초기화하지 마세요.
최초 clone은 로그인 없이 가능하지만 이후 push에는 본인 GitHub 인증이 필요합니다.

http://127.0.0.1:8787 에서 실행 화면을 확인합니다.
개발 모드는 `bash scripts/dev.sh` → http://127.0.0.1:5173 입니다.

### Windows PowerShell에서 개발용 웹 앱 실행

아래 절차는 Windows에서 아직 실행 검증하지 않았습니다. Tauri Windows 패키징과는 별개입니다.
`JAVA_HOME`이 Java 21을 가리키고 `java`, `node`, `npm`이 PATH에 있어야 합니다.

```powershell
git clone https://github.com/thisisnew/knowledge-forge.git
cd knowledge-forge
cd frontend
npm ci
npm test
npm run build
cd ../backend
.\mvnw.cmd clean verify
cd ..
$env:FORGE_DATA_DIR = Join-Path $PWD ".data"
java -jar backend/target/knowledge-forge-0.1.0.jar
```

현재 `scripts/smoke.mjs`는 Bash 실행 스크립트를 호출하므로 네이티브 PowerShell에서
그대로 실행되는 것으로 간주하지 마세요. Windows용 smoke 실행 경로는 후속 검증 항목입니다.

### 데이터 이전 주의

Git clone으로 이전되는 것은 소스·문서·가상 fixture뿐입니다. 기존 Workspace,
등록 Source, 원본 복사본, 분석 데이터는 자동 이전되지 않습니다.
필요하면 기존 앱을 완전히 종료하고 `FORGE_DATA_DIR`(기본 `.data`) 전체를
조직이 허용한 비공개 수단으로 복사하세요. DB나 회사 자료를 공개 Git에 올리지 마세요.
현재 스키마는 v20이고 구버전 앱으로 역이동하는 마이그레이션은 없습니다. 실행 전 기존 데이터 백업을 권장합니다.
근거로 선택한 파일은 별도 복사본이 남습니다. 원본 삭제만으로 이 복사본까지 지워지지 않습니다.
이전한 로컬 Source의 절대 경로가 새 PC에서 다르면 수집이 실패합니다.
Source 위치 수정 API는 아직 없으므로 원본 경로를 맞추거나 새 Source로 등록하세요.
기존 Source 삭제는 내부 스냅샷도 지우므로 백업 없이 재등록을 위해 삭제하지 마세요.

회사 네트워크에서 Maven Central/npm registry 접근이 제한되면 조직 정책에 맞는
프록시·사내 미러를 설정해야 합니다. 자격 증명이나 실제 회사 자료를 이 공개 저장소에
커밋하지 마세요. `fixtures/research-notes.md`로 먼저 동작을 확인합니다.

## 다음 개발 세션의 첫 작업

1. `README.md`, `docs/architecture.md`, `docs/api.md`, `docs/test-report.md`를 읽습니다.
2. 빌드 및 자동 테스트를 실행해 기준 상태를 확인합니다. 최신 결과는 `docs/answer-jobs.md`에 있습니다.
3. `fixtures/synthetic-project`를 등록·동기화하고 `measureTemperature` / `관측`으로 검색합니다.
4. **P4 문맥 실행**은 `docs/context-requests.md`의 v18 고정 요청·별도 승인·재검증까지 연결했습니다. 다음은 관계 검색과 설치형 CLI 설정 UX입니다. 실제 서비스 호출은 별도 명시 승인이 필요하므로 승인 없이 수행하지 마세요. 미리보기 digest는 승인이 아닙니다. Ollama로 되돌리지 마세요.
5. `CuratorService`와 `CliCuratorModel`은 이미 별도 AI DRAFT를 생성합니다. 기존 버전을 덮어쓰지 않습니다.
6. 사람 검토·게시, 현재 출처 탐색, 독립 지식 폴더·카드 배치, 안전한 Markdown 표시와 개발 도구 보안 갱신을 구현했습니다. 폴더 이름 변경/이동과 AI 결과 읽기 전용 비교도 구현했습니다. 다음은 오래된 근거의 명시적 교체·줄 단위 충돌 표시입니다. 관측 버전은 실행 바이너리 증명이 아니며 실제 실행 모델도 아직 미확인입니다.
7. 사람 편집 보호와 근거 없는 추론의 게시 차단을 먼저 검증합니다.
8. 필요 시 Phase 2 운영 확장(비공개 저장소·대용량·작업 취소)을 별도 기능 단위로 진행합니다.

Phase 1의 `library` 모듈은 `domain / application / adapter`로 나뉩니다.
후속 모듈은 빈 클래스부터 대량으로 생성하지 말고 유스케이스·포트·어댑터·테스트를
한 기능씩 추가합니다. 전체 완료 기준은 `docs/phase-plan.md`에 있습니다.

### 다음 코딩 에이전트에게 전달할 요청

```text
이 저장소의 AGENTS.md, HANDOFF.md, docs/backlog.md, docs/architecture.md,
docs/api.md, docs/phase3-foundation-report.md를 읽고 현재 코드와 비교해라.
Phase 1·2와 P3-1 수동 초안/버전/근거 스냅샷은 구현되어 있다. 먼저 빌드와 테스트로
기준 상태(백엔드 290개 통과 + live opt-in 1개 skip, 프런트엔드 160개)를 확인하고 실패를 숨기지 마라.
UI 기본 병렬 실행에서 시간 초과가 관측되었으므로 자원 제약 환경에서는 npm --prefix frontend test -- --maxWorkers=2로 검증해라.
사용자는 Ollama 대신 로그인된 Codex/Claude Code CLI 사용을 선택했다. docs/cli-ai.md를 읽어라.
별도 AI DRAFT·건별 승인·출력 검사·영속 작업·취소·멱등·재시작 상태 복구는 구현되었다.
docs/generation-jobs.md, docs/model-selection.md, docs/cli-observation.md를 읽어라.
건별 모델 선택·요청 모델 이력·승인 바인딩·실행 전 CLI 관측 이력은 구현했다.
docs/publication.md, docs/evidence-navigation.md, docs/knowledge-folders.md의 구현/한계를 읽어라.
docs/markdown-rendering.md의 표시 보안 정책을 유지해라. 개발 도구 보안 갱신, 폴더 이름 변경/이동, AI 결과 읽기 전용 비교는 완료했다. 거절·재검토·전체 채택과 수동 부분 반영과 CURRENT 근거 번호 통합도 구현했다. docs/evidence-merge.md를 읽고 다음은 오래된 근거의 명시적 교체·줄 단위 충돌 표시을 구현해라. 비교 화면에 자동 저장/외부 전송을 추가하지 마라.
docs/modeling.md와 docs/how-it-works.md도 코드와 맞게 유지해라. 사용자는 설치형 준비 후 체험한다.
최신 P4 구현은 docs/answer-jobs.md다. 승인 일회 소비·직전 재검증·실제 답변 작업·중단·재시작 복구까지 구현했다. 실제 외부 모델 검증은 아니며 fake CLI 테스트다.
docs/partial-answer.md의 Claude 임시 출력과 docs/codex-streaming.md의 선택적 Codex 실행기를 구현했다. 전용 프로필이 없으면 기존 exec 경로이며 자동 재전송하지 않는다. docs/answer-history.md의 이력, docs/conversations.md의 v17 보관함, docs/context-requests.md의 v18 불변 문맥·별도 승인·실행을 구현했다. 단순 보관함 정리는 전송이 아니다. 다음은 관계 검색과 설치형 CLI 설정 UX이다. 실제 서비스 검증은 별도 승인이 필요하다. 재연결로 AI를 재호출하지 말고 관측 종료와 명시적 작업 취소를 구분해라.
인증/원격 상태·실제 실행 모델 ID는 미구현 한계로 유지해라.
현재 대화 세션을 재사용한다고 주장하지 말고, 기존 로그인으로 새 CLI 요청을 생성해라.
사람 검토를 자동 의미 검증으로 과장하지 마라. 미구현 AI를 가짜 응답으로 완료 처리하지 마라.
사람 편집 보호, 검증 실패 시 이전 게시본 유지, Workspace 격리를 테스트해라.
회사 자료 대신 synthetic fixture를 사용하고 외부 LLM은 승인 없이 호출하지 마라.
GitHub Actions는 만들거나 수정하지 마라. 각 완료 항목의 문서와 실제 테스트 결과를 갱신해라.
```

## 유지할 개발 원칙

- 특정 업무 도메인을 하드코딩하지 않습니다.
- 검색과 LLM 생성은 분리하고, 질문마다 원본을 재분석하지 않습니다.
- 근거 없는 추론을 확정 지식으로 게시하지 않습니다.
- 새 지식은 검증 후 게시하고 사람이 편집한 버전은 자동으로 덮어쓰지 않습니다.
- 원본과 지식은 별도 수명주기로 관리합니다.
- 읽기 전용 수집, Source별 선택적 폴링, 명시적 외부 LLM 전송 승인을 적용합니다.
- 회사 저장소의 GitHub Actions Workflow를 수정하지 않습니다.
- 검증 전에는 가상 자료만 사용합니다.

## 알려진 한계

현재 단일 SQLite 연결/BLOB 저장, Source당 500개/50 MiB, 파일당 10 MiB, 경로 16단계 제한입니다.
로컬 빈 폴더는 가져오지 않습니다. 텍스트 추출은 500,000자까지이며 OCR/임베디드 문서 재귀 추출은 꺼져 있습니다.
공개 HTTPS URL과 공개 GitHub 기본 브랜치만 지원하며 토큰·SSH·사내 HTTP는 미지원입니다.
변경된 Source는 전체 스냅샷을 교체합니다. 지식이 인용한 원본만 당시 복사본을 보관하며 전체 원본 이력·Git diff는 Phase 5 범위입니다.
지식은 수동/AI DRAFT를 저장하고 안전한 Markdown 미리보기와 원문을 표시합니다. 사람 검토·명시적 게시와 독립 폴더·카드 배치가 있습니다. 게시 지식 검색은 구현했고 자동 의미 검증·관계 검색은 아직 없습니다.
Markdown 링크/각주 이동·이미지는 비활성입니다. 이전 개발 의존성 audit 경고 4개는 갱신 후 0개입니다. `docs/toolchain-report.md`를 확인하세요. audit 통과는 전체 보안 검증이 아닙니다.
근거 최신성은 조회 시 마지막 로컬 복사본과 비교하며 자동 갱신/의존 관계 전파는 아닙니다.
단일 문서 추출은 JVM 내부에서 동기 실행되므로 악성 문서 격리/강제 종료가 필요합니다.
별도 ArtifactStore·페이지네이션·OS 계정 간 인증·암호화·Windows 실행 검증은 남아 있습니다.
성능 측정은 가상 자료의 순차 HTTP 결과이며 LLM·대규모 동시 수집 성능을 나타내지 않습니다.
