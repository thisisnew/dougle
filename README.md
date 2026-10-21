# Knowledge Forge

PC에 원본 자료와 지식을 독립적으로 보관하는 로컬 지식 서재입니다.
현재 산출물은 **수집·검색·근거 연결 수동 초안 + 로그인된 Codex/Claude Code CLI 기반 AI 초안**입니다. 채팅은 아직 없습니다.

### 한눈에 보는 현재와 다음 단계

**수집 → 분석 → 검색 → 수동/AI 초안·근거 보존은 동작합니다. 검증·게시 → 채팅 → 자동 갱신 → 설치 앱은 다음 단계입니다.**

➡️ **[현재 구현과 앞으로의 설계도 보기](docs/design-map.md)** — 전체 구조, 데이터 흐름, 지식 게시 과정, SSE 순서

## 구현 범위

- Workspace 생성·삭제·전환 및 마지막 선택 복원
- Workspace별 원본 폴더 생성, 하위 폴더 탐색, 경로 이동
- 파일 복사본 업로드(10 MiB 이하), 다운로드, 삭제
- UTF-8 텍스트 미리보기(1 MiB 이하), 바이너리·초과 크기 안내
- 원본/지식 영역 분리, 수동 Markdown 초안·이전 버전·근거 원문 보존
- SQLite 영속 저장, Workspace 및 폴더 삭제 시 하위 자료 정리
- React + Tailwind CSS + shadcn/ui 패턴의 Button, Radix 접근성 Dialog
- 로컬 요청 방어, 오류 표시, 삭제 확인, 비동기 탐색 취소
- Source 등록: 로컬 폴더, 공개 HTTPS URL, 공개 GitHub 저장소
- 수동 동기화, 상태·오류·마지막 반영 버전, SHA-256 원본 해시
- 성공한 스냅샷만 원자적으로 반영, 실패 시 이전 자료 유지, 변경 없으면 기존 ID 유지
- Apache Tika 문서 추출, JavaParser 타입·메서드·생성자와 행 번호
- Lucene 10 + Nori로 내용·파일명·심볼 검색, 여러 사용자 지정 도메인 동시 검색
- SQLite 분석 결과/심볼 저장, 내구성 있는 인덱스 갱신 큐, 재시작 시 인덱스 복원

지식 검증·게시, 채팅, 자동 폴링, Git 증분 반영과 데스크톱 설치 패키지는 후속 범위입니다.
수동 초안은 모두 DRAFT(미검증)이며 근거 연결이 사실 검증을 의미하지 않습니다.

## 개발 환경

- JDK **21** (`JAVA_HOME` 설정, macOS에서는 실행 스크립트가 설치된 Java 21 자동 탐색)
- Node.js **22.12 이상** 또는 24, npm
- Git CLI (GitHub 수집에 사용)
- Maven은 포함된 Maven Wrapper로 내려받습니다. 전역 Maven 설치는 필요 없습니다.
- 최초 의존성 설치 시 인터넷 연결이 필요합니다. AI 생성은 건별 승인 후 외부 서버를 호출합니다.
- AI 기능은 로그인된 Codex 또는 Claude Code CLI가 필요합니다. Ollama 설치는 필요 없습니다.

```bash
cd knowledge-forge
bash scripts/build.sh
bash scripts/run.sh
```

브라우저에서 http://127.0.0.1:8787 을 엽니다. 빌드된 UI가 Spring Boot 실행 JAR에 포함됩니다.
종료는 해당 터미널에서 `Ctrl+C`입니다.

개발용 두 서버 실행:

```bash
cd frontend
npm ci
cd ..
bash scripts/dev.sh
```

개발 화면은 http://127.0.0.1:5173, API는 127.0.0.1:8787입니다.
Vite의 동일 출처 프록시가 API를 연결합니다.
두 포트 중 하나가 이미 사용 중이면 기존 개발 서버를 종료하세요.

## 사용 방법

1. **Workspace 만들기**에서 프로젝트 이름을 입력합니다.
2. **새 폴더**를 만들고 폴더 이름을 눌러 들어갑니다.
3. **파일 추가**로 `fixtures/research-notes.md`를 등록합니다.
4. 파일 이름을 누르면 오른쪽에 원문과 상세 정보가 나옵니다.
5. **원본 다운로드**로 보관한 바이트 그대로 내려받습니다.
6. 왼쪽 선택 상자로 Workspace를 전환합니다. 상단 경로로 상위 폴더에 돌아갑니다.
7. 자료 옆 휴지통이나 **Workspace 삭제**를 누른 뒤 확인하면 내부 복사본이 삭제됩니다.
8. 분석된 파일에서 **근거로 지식 초안 작성**을 눌러 제목·Markdown·추출 텍스트 행 범위를 입력합니다.
9. **지식 문서**에서 초안·근거·이전 버전을 열고 **초안 편집**으로 새 버전을 저장합니다.
10. 근거 원본은 별도 복사본으로 보존합니다. 원본 삭제 후에도 지식과 당시 원문은 남습니다.

11. 지식의 **AI 제공자 → 전송 내용 미리보기 → 동의 → 별도 AI 초안 생성**을 사용합니다.
    기존 로그인 계정을 이용하는 새 CLI 요청이며 이 코딩 대화 자체를 이어받지 않습니다.
    계정 요금·한도와 조직의 외부 전송 정책이 적용됩니다. 회사 자료 대신 가상 자료로 먼저 검증하세요.
12. **AI 생성 작업**에서 상태를 확인하고 **생성 중단** 또는 **생성된 초안 열기**를 사용합니다.
    화면 이동은 취소가 아니며 다시 열면 작업 이력을 조회합니다. 재시작 시 미완료 작업을 자동 재전송하지 않습니다.

현재 지식 UI는 평면 목록과 Markdown 원문 표시이며 폴더/렌더링/게시 기능은 없습니다.
AI 설정·보안 경계·실제 검증 결과는 [CLI 연동 안내](docs/cli-ai.md)를 참고하세요.
작업 ID/취소/재시작 복구와 최신 검증은 [AI 생성 작업 안내](docs/generation-jobs.md)에 있습니다.
Codex 실제 생성은 검증했습니다. 이 PC의 Claude 실제 호출은 인증 오류로 미검증이며 별도 로그인이 필요합니다.
근거의 CURRENT/STALE/MISSING은 마지막 수집된 로컬 복사본과 비교한 결과입니다.
외부 원본의 실시간 최신성이나 사실 검증 결과가 아닙니다. 근거 행은 추출 텍스트 기준이며 원본 페이지/행과 다를 수 있습니다.
지식 삭제 시 모든 버전과 다른 지식이 참조하지 않는 근거 복사본이 제거됩니다. 원본 자료는 유지됩니다.

앱에서 자료를 삭제해도 사용자가 업로드했던 PC의 원래 파일은 삭제되지 않습니다.
업로드 후 원래 파일이 바뀌어도 현재 복사본은 자동 갱신되지 않습니다.

### Source와 검색

1. **Source 등록**에서 종류·이름·위치를 입력합니다. 도메인은 원하는 분류명으로 지정할 수 있습니다.
2. 로컬 테스트는 `fixtures/synthetic-project`의 **절대 경로**를 입력합니다.
3. **동기화**를 누르면 원본 영역에 Source 이름의 폴더가 생깁니다.
4. `measureTemperature`로 검색하면 Java 심볼이 있는 파일을 찾을 수 있습니다.
5. 파일을 선택하면 원문, 추출된 텍스트, 심볼과 행 번호를 확인할 수 있습니다.
6. 도메인 체크박스를 여러 개 선택하면 선택한 분류를 함께 검색합니다.
7. 원본을 변경한 뒤 다시 동기화합니다. 오류가 발생하면 기존 반영 버전이 유지됩니다.

Phase 1에서 등록한 기존 파일은 **다시 분석**을 눌러 검색에 추가합니다.
Source 소속 자료는 탐색기에서 개별 변경하지 않고 Source 동기화/삭제로 관리합니다.
새 Source는 등록만으로 외부 통신하지 않으며, 수동 동기화를 눌러야 수집합니다.

현재 공개 HTTPS(443), 공개 GitHub 기본 브랜치만 지원합니다. 비공개 저장소 토큰,
사내 HTTP 서버, SSH·서브모듈·심볼릭 링크·OCR는 지원하지 않습니다.
Source당 500개 파일/50 MiB, 파일당 10 MiB, 경로 깊이 16단계 제한이 있습니다.
`.git`, `.env*`, `.ssh`, `.aws`, `node_modules`, `target`, `dist`, 앱 데이터·캐시는 제외합니다.
텍스트 추출은 파일당 500,000자 이내입니다.

## 데이터와 실행 설정

- 기본 저장소: 프로젝트의 `.data/knowledge-forge.sqlite`
- `FORGE_DATA_DIR`: 저장 디렉터리 절대 경로 재정의
- `PORT`: 단일 실행 JAR의 포트 재정의(기본 8787)
- `JAVA_HOME`: Java 21 경로
- 빌드 캐시: `.m2`, `.tools`, `frontend/node_modules`
- 백업은 앱을 종료한 뒤 `.data` 디렉터리 전체를 복사하세요.
- 기본 저장소는 암호화되지 않습니다. 현재는 단일 OS 사용자 로컬 개발용입니다.

```bash
FORGE_DATA_DIR="$HOME/KnowledgeForgeData" PORT=8787 bash scripts/run.sh
```

## 테스트

```bash
bash scripts/maven.sh test
cd frontend
npm test
npm run build
cd ..
node scripts/smoke.mjs
```

마지막 명령은 빌드된 JAR를 임시 저장소·임시 포트로 실행합니다.
실제 HTTP, 파일 왕복, 재시작 영속성, 삭제와 작은 합성 데이터셋 성능을 검증하고
`docs/performance-phase3-foundation.json`을 생성합니다. 지식·근거의 재시작 보존도 검증하며 개발 데이터는 사용하지 않습니다.
AI 작업 검사는 임시 서버에만 테스트 전용 fake CLI를 지정합니다. 실제 AI 모델의 성공/성능으로 해석하지 마세요.
`node scripts/smoke.mjs --live-public`은 example.org와 이 공개 프로젝트 저장소의
읽기 전용 수집까지 검사하므로 인터넷 연결이 필요합니다.

## 산출물

- **다른 PC에서 이어서 개발:** [구현 현황 및 인수인계](HANDOFF.md)
- **다음 작업 전체 체크리스트:** [남은 개발 작업과 완료 조건](docs/backlog.md)
- 실행 JAR: `backend/target/knowledge-forge-0.1.0.jar`
- 프런트엔드 번들: `frontend/dist`
- [아키텍처](docs/architecture.md)
- [API 명세](docs/api.md)
- [Phase 완료 기준과 로드맵](docs/phase-plan.md)
- [테스트 결과](docs/test-report.md)
- [Phase 2 검증 결과와 한계](docs/phase2-report.md)
- [Phase 3 기반 검증 결과와 한계](docs/phase3-foundation-report.md)

Mac `.app`/`.dmg`는 Phase 6 산출물이며 현재 제공하지 않습니다.
