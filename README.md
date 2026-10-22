# Knowledge Forge

**내 자료를 모아 검색하고, 출처가 있는 지식을 만드는 Mac 앱**입니다.
파일·폴더·웹·GitHub를 로컬에 보관하고, 정리된 지식과 근거 기반 AI 답변을 만듭니다.

## 설치

| Mac 종류 | 설치 파일 / 검증 상태 |
|---|---|
| Intel | [0.1.1 미리보기 다운로드](https://github.com/thisisnew/knowledge-forge/releases/tag/v0.1.1-preview.1) · 설치 복사본 실행 검증 완료 |
| Apple Silicon (M 시리즈) | [ARM 실험적 미리보기 다운로드](https://github.com/thisisnew/knowledge-forge/releases/tag/v0.1.1-arm-preview.1) · 교차 빌드/정적 검사 통과, **실기기 실행 미검증** |

Java는 앱에 포함됩니다. **Apple 공증은 이번 범위에서 제외**하며, 회사 보안 정책에 따라 설치가 제한될 수 있습니다.
[설치·백업·삭제 안내](docs/macos-install.md)를 먼저 읽으세요. Windows는 후속 지원입니다.

## 어떤 일을 할 수 있나요?

1. **자료 모으기** — 프로젝트별 Workspace에 파일·폴더·공개 HTTPS·공개 GitHub를 등록합니다.
2. **찾기** — 원본·게시된 지식을 검색하고 여러 도메인을 함께 선택합니다.
3. **지식 만들기** — 출처를 연결한 Markdown 초안을 직접 쓰거나 AI로 생성합니다.
4. **검토·게시** — 사람이 확인한 버전만 게시하며 이전 버전·사람 편집을 보존합니다.
5. **질문하기** — 근거부터 확인하고 AI 요청을 승인하면 SSE로 답변·상태를 받습니다. 중단과 선택 문맥 이어 질문도 지원합니다.
6. **갱신하기** — Source별 자동 갱신을 켭니다. Git 변경 blob·웹 조건부 요청을 사용하며 실패하면 이전 자료를 유지합니다.

**자료 등록만으로 AI가 자동 호출되지는 않습니다.** AI는 로그인된 Codex/Claude Code CLI에 승인한 내용을 보내는 새 요청입니다.
현재 코딩 대화를 공유하지 않으며, CLI 설치·로그인은 별도로 필요합니다. Ollama는 요구하지 않습니다.

## 구조를 쉽게 보면

```text
Mac 앱 (Tauri)
  ├─ React 화면
  └─ 동봉 Java / Spring Boot
       ├─ 원본 수집 → Tika·JavaParser → Lucene/Nori 검색
       ├─ SQLite: Workspace·원본·지식·출처·승인·답변 이력
       └─ 사용자 승인 → Codex/Claude CLI → 별도 초안 또는 답변
```

원본과 지식은 독립 보관합니다. 질문할 때 전체 자료를 다시 분석하지 않습니다.
외부 DB 서버·벡터 DB·Elasticsearch는 필요 없습니다. 회사 GitHub Actions를 수정하지 않습니다.
Intel/ARM은 화면·JAR·DB를 공유하고 Tauri 실행 파일과 Java 런타임만 CPU에 맞게 묶습니다.

## 문서

- **처음 사용:** [5분 사용법](docs/how-it-works.md) · [전체 문서 목차](docs/README.md)
- **구조 이해:** [그림 설계도](docs/design-map.md) · [모델과 실제 코드 대조](docs/modeling.md)
- **개발:** [아키텍처](docs/architecture.md) · [API](docs/api.md) · [Mac 빌드](docs/macos-build.md)
- **현재와 다음:** [인수인계](HANDOFF.md) · [단계별 현황](docs/phase-plan.md) · [상세 백로그](docs/backlog.md)
- **검증:** [0.1.1 결과](docs/release-0.1.1-report.md) · [ARM 확인 범위](docs/apple-silicon.md)

## 개발 실행

Java 21, Node.js 22.12+ 또는 24, Git이 필요합니다. Maven은 저장소 Wrapper를 사용합니다.

```bash
bash scripts/build.sh
bash scripts/run.sh
```

`http://127.0.0.1:8787`에서 엽니다. 개발 데이터는 `.data`에 저장되며 설치 앱 데이터와 다릅니다.
`FORGE_DATA_DIR`로 저장 위치, `PORT`로 포트를 지정할 수 있습니다. 개발 서버 두 개는 `bash scripts/dev.sh`로 실행합니다.

```bash
bash scripts/maven.sh test
(cd frontend && npm test && npm run build)
node scripts/smoke.mjs
bash scripts/desktop-cargo.sh test --locked
```

## 현재 한계

- DB v21. 업데이트 전 앱을 종료하고 데이터 폴더 전체를 백업하세요. 구버전으로 다시 열지 않습니다.
- 파일당 10 MiB, Source당 500개/50 MiB. 사설 GitHub·SSH·OCR·대규모 저장소 확장은 후속입니다.
- CURRENT는 마지막 로컬 수집본과 일치한다는 뜻이지 사실·원격 최신성 보증이 아닙니다.
- 자동 의미 검증, 영속 Git 캐시, 자동 업데이트는 미지원입니다.
- 최신 통합 검사: 백엔드 350·UI 195·Rust 12 통과. 실제 AI opt-in 테스트 1개는 미실행입니다.
- ARM 교차 빌드와 실제 ARM 기기 실행 검증을 구분합니다. 첫 체험은 회사 자료가 아닌 가상 자료로 진행하세요.
