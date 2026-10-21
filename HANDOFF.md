# Knowledge Forge — 구현 현황 및 다음 작업

기록일: 2026-10-21 (Asia/Seoul)

Phase 2 기준 커밋은 `d4cd1e8`이며, 이후 Phase 3 P3-1 구현과 검증 기록을 추가했습니다.
전체 미완료 체크리스트와 기능별 인수 조건은 [남은 작업](docs/backlog.md)에 있습니다.
현재/예정 구조를 그림으로 보려면 [한눈에 보는 설계도](docs/design-map.md)를 먼저 읽으세요.

## 현재 상태

**Phase 1·2와 Phase 3 P3-1(지식/근거 영속 모델 및 수동 초안 UI/API)을 구현했습니다.**
Java 21 / Spring Boot 4.1.1 / React / TypeScript / Vite / Tailwind / SQLite JDBC를
사용하는 실제 실행 가능한 로컬 웹 앱입니다. 최종 배포 형태는 Tauri 2 데스크톱 앱입니다.

현재 Source 수동 수집, 문서·Java 분석, 로컬 검색, 근거 연결 수동 지식 초안·버전을 사용할 수 있습니다.
LLM 지식 생성·채팅·Source 자동 폴링은 아직 구현하지 않았습니다.
GitHub Actions Workflow도 생성하거나 수정하지 않았습니다.

### 완료

- Workspace 생성·삭제·전환, 활성 Workspace 영속 저장
- 원본 폴더 생성·계층 탐색·상위 경로 이동
- 10 MiB 이하 파일 업로드, 원본 바이트 다운로드, 폴더/자료 삭제
- 1 MiB 이하 UTF-8 텍스트 미리보기, HTML 실행 방지
- 원본과 지식 영역 분리, 수동 초안과 미구현 AI 생성·게시의 명확한 구별
- SQLite FK cascade / WAL / 스키마 버전 3 / 중복 이름 보호
- Workspace 간 자료 접근 격리, 로컬 Host/Origin/변경 헤더 검사
- React 비동기 취소, 오류 표시, 삭제 확인 대화상자
- UI를 포함한 실행 JAR 빌드, Maven Wrapper, 개발·빌드·실행 스크립트
- 가상 자료 기반 백엔드 48개 / 프런트엔드 17개 자동 테스트 통과
- 실제 JAR 재시작 후 원본·선택 복원, 삭제 영속성, HTTP 성능 측정
- Source 등록·삭제·수동 동기화: 로컬 폴더 / 공개 HTTPS URL / 공개 GitHub 기본 브랜치
- 원본 해시·마지막 revision·오류·반영 시각, 실패 시 이전 스냅샷 보존
- Tika HTML/PDF/일반 문서 추출, JavaParser 심볼·행 번호와 원문 보관
- Lucene 10.3.1 + Nori 검색, 도메인 다중 선택, Workspace 검색 격리
- 영속 분석 결과와 SQLite 인덱스 갱신 큐, 시작 시 저장된 분석 결과로 인덱스 복원
- SQLite 스키마 1→2 마이그레이션과 원본·활성 Workspace 보존
- Source 관리 파일 변경 방지, 사설 주소·리다이렉트·심볼릭 링크·용량 제한
- 지식 수동 초안 생성·독립 삭제, 불변 버전, expectedVersion 편집 충돌 409
- 당시 원본 바이트/추출 텍스트/해시/revision 스냅샷, 원본 삭제 후에도 근거 보존
- 근거 CURRENT/STALE/MISSING 조회, v1/v2→v3 마이그레이션

### 미구현

| Phase | 작업 | 상태 |
|---|---|---|
| 2 확장 | 비공개 저장소 자격 증명, 대용량 ArtifactStore, 작업 취소·격리 파서 | 남음 |
| 3 | P3-1 완료, 수동 초안 UI 일부 완료. Ollama Curator·검증·게시·지식 폴더는 남음 | 진행 중 |
| 4 | 기존 다중 도메인 검색에 Ollama, 근거 선표시 SSE, 답변 중단, 외부 Provider 승인 연결 | 미착수 |
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
현재 스키마는 v3이고 구버전 앱으로 역이동하는 마이그레이션은 없습니다. 실행 전 기존 데이터 백업을 권장합니다.
근거로 선택한 파일은 별도 복사본이 남습니다. 원본 삭제만으로 이 복사본까지 지워지지 않습니다.
이전한 로컬 Source의 절대 경로가 새 PC에서 다르면 수집이 실패합니다.
Source 위치 수정 API는 아직 없으므로 원본 경로를 맞추거나 새 Source로 등록하세요.
기존 Source 삭제는 내부 스냅샷도 지우므로 백업 없이 재등록을 위해 삭제하지 마세요.

회사 네트워크에서 Maven Central/npm registry 접근이 제한되면 조직 정책에 맞는
프록시·사내 미러를 설정해야 합니다. 자격 증명이나 실제 회사 자료를 이 공개 저장소에
커밋하지 마세요. `fixtures/research-notes.md`로 먼저 동작을 확인합니다.

## 다음 개발 세션의 첫 작업

1. `README.md`, `docs/architecture.md`, `docs/api.md`, `docs/test-report.md`를 읽습니다.
2. 빌드 및 자동 테스트를 실행해 기준 상태를 확인합니다. 최신 결과는 `docs/phase3-foundation-report.md`에 있습니다.
3. `fixtures/synthetic-project`를 등록·동기화하고 `measureTemperature` / `관측`으로 검색합니다.
4. **다음 핵심 기능은 P3-2** Ollama Curator입니다. P3-1을 다시 구현하지 마세요.
5. `knowledge` 모듈의 불변 버전·근거 스냅샷과 수동 작성 플래그를 사용해 AI 초안을 별도 추가하세요.
6. Ollama Curator 어댑터는 출력 검증·실패 시 이전 게시 버전 유지 테스트와 함께 추가합니다.
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
기준 상태(백엔드 48개, 프런트엔드 17개)를 확인하고 실패를 숨기지 마라.
다음 작업은 backlog의 P3-2 Ollama Curator다. 기존 지식 영속 모델을 재사용하되
AI 생성은 사람이 편집한 버전을 덮어쓰지 않고 별도 초안으로 만들어라.
이후 P3-3 검증·게시를 완성해라. 미구현 AI를 가짜 응답으로 완료 처리하지 마라.
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
지식은 수동 DRAFT만 저장하고 Markdown 원문으로 표시합니다. AI·검증·게시·폴더·지식 검색은 아직 없습니다.
근거 최신성은 조회 시 마지막 로컬 복사본과 비교하며 자동 갱신/의존 관계 전파는 아닙니다.
단일 문서 추출은 JVM 내부에서 동기 실행되므로 악성 문서 격리/강제 종료가 필요합니다.
별도 ArtifactStore·페이지네이션·OS 계정 간 인증·암호화·Windows 실행 검증은 남아 있습니다.
성능 측정은 가상 자료의 순차 HTTP 결과이며 LLM·대규모 동시 수집 성능을 나타내지 않습니다.
