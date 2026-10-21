# Knowledge Forge

PC에 원본 자료와 지식을 독립적으로 보관하는 로컬 지식 서재입니다.
현재 산출물은 **수집·검색·근거 연결 수동 초안 + 로그인된 Codex/Claude Code CLI 기반 AI 초안**입니다. 채팅은 아직 없습니다.

### 한눈에 보는 현재와 다음 단계

최신 연결: [Codex 선택적 스트리밍](docs/codex-streaming.md). 전용 로그인 폴더를 지정하면 중간 출력을 표시합니다. 기본값은 기존 완료 답변이며 실제 서비스 생성 검증은 별도입니다.

이전 개선: [Claude 생성 중 임시 출력](docs/partial-answer.md). 미검증 텍스트를 표시하고, 검사된 최종 답변만 저장합니다. 실패·중단 때 임시 글을 지웁니다. 다중 대화는 남았습니다. 검증은 fake CLI이며 실제 외부 모델 검증과 구분합니다.

큰 단계는 **① 답변·SSE 채팅 ② 선택적 자동 갱신 ③ Mac 설치형**이 남았습니다. 비공개 저장소·대용량·자동 의미 검증 등 운영 확장은 별도입니다.

**수집 → 분석 → 검색 → 수동/AI 초안 → 사람 검토·명시적 게시까지 동작합니다. 안전한 Markdown 표시도 지원합니다. 자동 의미 검증 → 채팅 → 자동 갱신 → 설치 앱은 후속 단계입니다.**

➡️ **[현재 구현과 앞으로의 설계도 보기](docs/design-map.md)** — 전체 구조, 데이터 흐름, 지식 게시 과정, SSE 순서

처음 읽는다면 **[쉬운 작동 원리·사용법](docs/how-it-works.md)** → **[모델링·코드 대조표](docs/modeling.md)** 순서로 보세요.
사용자 체험은 [Mac 설치형 준비 기준](docs/desktop-preview.md)을 통과한 뒤 진행합니다. 현재 설치 파일은 없습니다.

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

자동 의미 검증, 채팅, 자동 폴링, Git 증분 반영과 데스크톱 설치 패키지는 후속 범위입니다.
불변 초안은 DRAFT이며 검토/게시 이력을 별도로 보존합니다. 사람 검토와 명시적 게시를 지원하지만 자동 사실 검증은 아닙니다. [사용법·한계](docs/publication.md).

최신 DB 스키마는 v16입니다. 업데이트 전 앱을 종료하고 데이터 디렉터리를 백업하세요.

지식 근거의 **현재 원본에서 보기**로 원본 폴더·미리보기로 이동할 수 있습니다.
동기화 후 위치 해석과 삭제/변경 경고는 [출처 탐색 가이드](docs/evidence-navigation.md)를 참고하세요. 독립 지식 폴더·카드 배치도 지원합니다. [사용법](docs/knowledge-folders.md).

## 개발 환경

- JDK **21** (`JAVA_HOME` 설정, macOS에서는 실행 스크립트가 설치된 Java 21 자동 탐색)
- Node.js **22.12 이상** 또는 24, npm
- Git CLI (GitHub 수집에 사용)
- Maven은 포함된 Maven Wrapper로 내려받습니다. 전역 Maven 설치는 필요 없습니다.
- 최초 의존성 설치 시 인터넷 연결이 필요합니다. AI 생성은 건별 승인 후 외부 서버를 호출합니다.
- AI 기능은 로그인된 Codex 또는 Claude Code CLI가 필요합니다. Ollama 설치는 필요 없습니다.
- 모델 고정·CLI 설치 확인 방법과 남은 범위는 [모델 설정 가이드](docs/cli-settings.md)를 확인하세요.
- 건별 모델 선택·브라우저 선택 저장·요청 모델 이력과 v5 업그레이드는 [건별 모델 가이드](docs/model-selection.md)를 확인하세요.
- 실행 전 CLI 관측 이력(v6 도입)은 [CLI 관측 가이드](docs/cli-observation.md)를 확인하세요.

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

완료된 AI 작업에서 **원래 문서와 비교**를 누르면 생성 당시·현재·AI 최초 결과를 함께 확인합니다.
비교는 읽기 전용이며 자동 덮어쓰기는 없습니다. [비교 안내](docs/generation-comparison.md). 별도 [처리 기록](docs/proposal-decisions.md)에서 거절·재검토 이유를 저장하고, [전체 채택](docs/proposal-acceptance.md)에서 미편집 제안을 새 초안으로 반영할 수 있습니다. 사람 편집을 남기면서 일부만 추가하려면 [부분 반영 편집](docs/proposal-merge.md)을 사용하세요. 현재 수집본과 일치하는 근거끼리는 [번호 통합](docs/evidence-merge.md)도 선택할 수 있습니다.

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

지식 UI는 전체/폴더별 목록, 카드 배치, Markdown 미리보기·원문, 사람 검토·명시적 게시를 제공합니다.
제목·목록·표·코드를 표시하며 HTML 실행, 링크 이동, 이미지 로딩은 차단합니다.
카드를 열고 **Markdown 원문 보기**를 펼치면 저장된 원문을 확인할 수 있습니다.
[표시 정책·검증·남은 한계](docs/markdown-rendering.md). 자동 의미 검증은 아직 없습니다.
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
