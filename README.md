# Knowledge Forge

PC에 원본 자료와 지식을 독립적으로 보관하는 로컬 지식 서재입니다.
현재 산출물은 **Phase 1 + Phase 2의 수집·분석·검색**입니다. LLM 지식 생성과 채팅은 아직 없습니다.

## 구현 범위

- Workspace 생성·삭제·전환 및 마지막 선택 복원
- Workspace별 원본 폴더 생성, 하위 폴더 탐색, 경로 이동
- 파일 복사본 업로드(10 MiB 이하), 다운로드, 삭제
- UTF-8 텍스트 미리보기(1 MiB 이하), 바이너리·초과 크기 안내
- 원본/지식 영역 분리, 지식 영역의 명시적 빈 상태
- SQLite 영속 저장, Workspace 및 폴더 삭제 시 하위 자료 정리
- React + Tailwind CSS + shadcn/ui 패턴의 Button, Radix 접근성 Dialog
- 로컬 요청 방어, 오류 표시, 삭제 확인, 비동기 탐색 취소
- Source 등록: 로컬 폴더, 공개 HTTPS URL, 공개 GitHub 저장소
- 수동 동기화, 상태·오류·마지막 반영 버전, SHA-256 원본 해시
- 성공한 스냅샷만 원자적으로 반영, 실패 시 이전 자료 유지, 변경 없으면 기존 ID 유지
- Apache Tika 문서 추출, JavaParser 타입·메서드·생성자와 행 번호
- Lucene 10 + Nori로 내용·파일명·심볼 검색, 여러 사용자 지정 도메인 동시 검색
- SQLite 분석 결과/심볼 저장, 내구성 있는 인덱스 갱신 큐, 재시작 시 인덱스 복원

지식 생성, 채팅, 자동 폴링, Git 증분 반영, 지식 버전 관리와 데스크톱 설치 패키지는 후속 Phase의 범위입니다.

## 개발 환경

- JDK **21** (`JAVA_HOME` 설정, macOS에서는 실행 스크립트가 설치된 Java 21 자동 탐색)
- Node.js **22.12 이상** 또는 24, npm
- Git CLI (GitHub 수집에 사용)
- Maven은 포함된 Maven Wrapper로 내려받습니다. 전역 Maven 설치는 필요 없습니다.
- 최초 의존성 설치 시 인터넷 연결이 필요합니다. 실행 중 외부 AI 호출은 없습니다.

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
8. **지식 문서**는 독립 영역이며, Phase 3 전까지 비어 있습니다.

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
`docs/performance-phase2.json`을 생성합니다. 개발 데이터는 사용하지 않습니다.
`node scripts/smoke.mjs --live-public`은 example.org와 이 공개 프로젝트 저장소의
읽기 전용 수집까지 검사하므로 인터넷 연결이 필요합니다.

## 산출물

- **다른 PC에서 이어서 개발:** [구현 현황 및 인수인계](HANDOFF.md)
- 실행 JAR: `backend/target/knowledge-forge-0.1.0.jar`
- 프런트엔드 번들: `frontend/dist`
- [아키텍처](docs/architecture.md)
- [API 명세](docs/api.md)
- [Phase 완료 기준과 로드맵](docs/phase-plan.md)
- [테스트 결과](docs/test-report.md)
- [Phase 2 검증 결과와 한계](docs/phase2-report.md)

Mac `.app`/`.dmg`는 Phase 6 산출물이며 현재 제공하지 않습니다.
