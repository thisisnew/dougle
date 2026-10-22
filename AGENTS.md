# Knowledge Forge 개발 지침

- GitHub 소개 페이지(README)는 비개발자 고객 기준의 짧은 소개·실제 화면·설치법·기술 스택·상세 문서 링크만 담는다. '현재 한계'나 긴 내부 설명은 README에서 제외하고 설치 안내/검증 문서에 보존한다. 미완료 기능을 광고하지 않는다. 사용자 요청 순서가 있으면 소개글 작성을 마친 다음 디자인을 변경한다.

- 사용자 상시 요청: 개발·수정 완료 시 설치 파일도 갱신한다. Intel/Apple Silicon별 빌드·해당 검증·GitHub Release 업로드·체크섬 확인까지 완료 기준에 포함한다. README 소개/사용법이 달라지면 함께 수정하고 UI 변경에는 가상 자료로 실제 화면 스크린샷을 제공한다. 소스만 푸시한 상태를 배포 완료라고 보고하지 않는다. 문서만 수정한 경우에는 실행 파일 재빌드가 불필요한지 명시한다. 공증 제외·ARM 실기기 미검증·외부 AI 별도 승인 원칙은 유지한다.

- 최신 재점검은 `docs/model-audit.md`와 `scripts/model-audit.mjs`를 따른다. 전체 파일 목록·구조 검사와 수동 의미 검토/실행 커버리지는 구분한다. 선택 문맥 고정·승인·실행은 이미 구현되어 있다.
- 사용자는 CLI 설치/경로 입력 없는 제공자 선택·연결 UX를 요청했다. 0.1.2 개발 소스의 선택·인증·로그인과 Codex 동봉 빌드는 `docs/provider-connection.md`를 따른다. 도구 준비·인증·원격 이용 가능성을 분리하며, 실제 계정·Claude 동봉·배포 완료는 각 검증 전까지 미완료로 표시한다.

- Apple 공증은 사용자 요청으로 현재 범위에서 제외한다. ARM64 지원·교차 빌드·실제 기기 검증 상태는 `docs/apple-silicon.md`를 따른다. Intel 호스트에서 ARM 실행 성공으로 보고하지 않는다.
- `scripts/package-macos.sh`는 `FORGE_MACOS_TARGET`/`FORGE_TARGET_JAVA_HOME`을 지원한다. 공유 `resources/runtime`은 마지막 타깃으로 교체되므로 병렬 패키징하지 않는다. 설치 검증은 명시적 DMG 경로를 사용하며 ARM 정적 검사는 `--static-only`로 구분한다.
- 사용자용 문서 목차는 `docs/README.md`다. 앱 소개·설치·사용·설계·모델·검증 상태를 함께 갱신하며 과거 보고서와 현재 상태를 구별한다.

- 시작할 때 `HANDOFF.md`, `docs/architecture.md`, `docs/phase-plan.md`를 읽는다.
- 쉬운 모델링/매뉴얼은 `docs/modeling.md`, `docs/how-it-works.md`다. 기능 변경 시 실제 코드와 대조해 구현/예정을 구분한다. 사용자 체험은 `docs/desktop-preview.md`의 Mac 설치형 기준 이후다.
- 이어서 개발할 때 `docs/backlog.md`의 미완료 항목과 인수 조건을 확인하고 완료 증거와 함께 갱신한다.
- Phase 1·2, P3-1, P3-2 CLI AI 초안/전송 승인이 구현되어 있다. `docs/cli-ai.md`의 검증/남은 범위를 확인한다. 사람 검토 후 명시적 게시 경로는 `docs/publication.md`를 확인한다. 자동 의미 검증은 없다.
- 사용자 요청으로 Ollama 대신 로그인된 Codex/Claude Code CLI를 사용한다. 현재 코딩 대화가 아닌 새 요청이며 외부 전송은 건별 승인한다.
- SQLite v4 생성 작업 상태·취소·멱등·재시작 복구가 추가되었다. `docs/generation-jobs.md`를 확인한다. smoke의 fake CLI는 테스트 전용이며 실제 AI 성공으로 보고하지 않는다.
- 모델 고정·승인 바인딩·CLI 설치 진단은 `docs/cli-settings.md`를 확인한다. 설치 버전 확인을 로그인/원격 연결 성공으로 표시하지 않는다.
- 건별 모델 선택·브라우저 선택 저장·요청 모델 작업 이력은 `docs/model-selection.md`를 확인한다. v5에서 도입했으며 과거 모델 미기록 값은 null로 보존한다.
- 후속 CLI 관측 이력은 `docs/cli-observation.md`를 확인한다. CLI 관측은 v6에서 도입했으며 실행 전 관측 버전을 정확한 생성 바이너리 버전으로 단정하지 않는다.
- 현재 스키마는 v21이다. CLI 경로 저장·재시작 적용·승인 binding은 `docs/cli-launch-settings.md`를 따른다. URL 조건부 요청은 `docs/url-conditional.md`, Source opt-in 자동 갱신은 `docs/source-polling.md`를 따른다. v0.1.0-preview.1 DMG는 v18 기준이며 더 최신 DB로 다운그레이드 실행하지 않는다. 문맥 고정 요청은 `docs/context-requests.md`를 따른다. 검토/게시 이력은 불변 DRAFT와 별도이며 변경 근거·편집/게시 충돌을 차단한다.
- AI 결과 비교는 `docs/generation-comparison.md`를 확인한다. 생성 당시/현재/최초 결과를 읽기 전용 조회한다. 거절·재검토 이력은 `docs/proposal-decisions.md`에 구현했다. 명시적 전체 채택은 `docs/proposal-acceptance.md`에 구현했다. 동일 근거의 수동 부분 반영은 `docs/proposal-merge.md`를 따르며, 현재 수집본과 일치하는 근거의 번호 통합은 `docs/evidence-merge.md`에 구현했다. 오래된 근거를 새 원본으로 교체하는 재연결은 아직 없다.
- P3-4 출처 탐색 연결은 `docs/evidence-navigation.md`를 확인한다. 원본 해석은 Source ID+경로 또는 업로드 ID이며 독립 지식 폴더·카드 배치는 `docs/knowledge-folders.md`를 확인한다.
- 기능 하나마다 유스케이스·포트·어댑터와 실제 테스트를 완성한다.
- 사용자는 완성까지 연속 진행하도록 요청했다. 기능마다 멈추지 말고 검증·문서·푸시 후 다음 항목으로 이어간다. 외부 AI 실제 전송과 배포 자격 증명은 별도 승인 경계다.
- 이전 문맥의 읽기 전용 미리보기는 `docs/conversation-context.md`, 명시적 고정 저장·승인·실행은 `docs/context-requests.md`를 따른다. 저장 전 문맥 해시로 기존 단일 질문을 승인할 수 없다.
- Source 파일 ID 보존·변경 분석은 `docs/incremental-snapshots.md`, Git 변경 blob 재사용은 `docs/git-incremental.md`, 설치형 토큰/수명주기는 `docs/desktop-backend.md`를 따른다. 영속 Git fetch/diff 캐시나 실제 GitHub 성능 검증으로 확대 해석하지 않는다.
- Intel Mac 미리보기 패키지와 검증은 `docs/desktop-report.md`, 재현은 `docs/macos-build.md`를 따른다. 공증·Apple Silicon·실제 AI 검증으로 확대 해석하지 않는다. 빌드 중인 JAR/앱 번들을 실행 검증과 동시에 덮어쓰지 않는다.
- 질문별 이력 조회는 `docs/answer-history.md`, v17 완료 답변 보관함은 `docs/conversations.md`를 따른다. 보관함의 정리 순서를 실제 문맥 전송 턴으로 취급하지 않는다. 보관함 조회 자체는 새 AI 호출·승인·이전 문맥 전송을 하지 않는다. 이어 질문은 별도 `docs/context-requests.md` 경로다.
- Codex 스트림은 `docs/codex-streaming.md`의 전용 프로필 opt-in 실행기까지 답변 작업에 연결했다. 기본 exec/지식 생성 경로는 유지하며 실패 시 자동 재전송하지 않는다. 가상 CLI 테스트를 실제 서비스 생성 성공으로 보고하지 않는다. 선택 문맥 기반 다중 대화는 `docs/context-requests.md`에 구현되어 있다.
- 지식 Markdown 표시 보안 경계는 `docs/markdown-rendering.md`를 따른다. 링크/이미지 자동 로딩이나 raw HTML 실행을 추가하지 않는다.
- Java 21, Spring Boot 4.1.x, React/TypeScript/Vite, SQLite JDBC를 유지한다.
- 도메인 모델은 Spring, HTTP, JDBC를 참조하지 않는다.
- 후속 검색은 Lucene 10 + Nori 및 심볼/관계 인덱스를 사용한다. 외부 DB 서버는 추가하지 않는다.
- `docs/relations.md`의 관련 자료는 동일 선언 이름/종류·공통 인용 위치 연결이다. 함수 호출·의미 관계 또는 자동 답변 근거 확장으로 보고하지 않는다.
- 원본 검색 미리보기의 본문 위치/행 범위는 `docs/search-evidence.md`를 따른다. 확정 인용이나 최신성 보증으로 취급하지 않는다. 게시 지식 통합 검색·v13 큐·초안 제외·도메인/최신성 규칙은 `docs/published-search.md`를 따른다.
- 답변용 근거 묶음의 v14 로컬 보관·불변 인용·해시·제외 규칙·삭제 정책은 `docs/evidence-bundles.md`를 따른다. 저장은 전송 승인이 아니며 후속 AI 전송 전 재검증을 생략하지 않는다.
- 읽기 전용 재검증·모델별 답변 요청 미리보기는 `docs/bundle-validation.md`를 따른다. 미리보기 digest는 승인이 아니다. v15 건별 승인·철회·만료는 `docs/answer-approval.md`, v16 실제 답변 작업·일회 소비·재검증·중단은 `docs/answer-jobs.md`를 따른다. 요청문은 V2이며 오래된 승인은 재확인한다. 선택 문맥의 이어 질문은 `docs/context-requests.md`를 따른다. fake CLI 성공을 실제 외부 모델 검증으로 보고하지 않는다.
- 후속 읽기 전용 작업 관측 SSE API는 `docs/answer-events.md`, 화면은 `docs/answer-events-ui.md`, Claude 미검증 임시 출력은 `docs/partial-answer.md`를 따른다. 임시 글은 메모리만 사용하고 실패·중단 때 폐기한다. 재연결로 생성/전송하지 않으며 관측 종료와 명시적 작업 취소를 구분한다.
- 특정 업무 도메인을 하드코딩하지 않는다. 테스트는 `fixtures`의 가상 자료만 사용한다.
- 원본과 지식 수명주기를 분리하고, 검증 후 게시와 수동 편집 보호 원칙을 지킨다.
- 수집은 읽기 전용, 폴링은 Source별 opt-in, 외부 LLM 전송은 명시적 승인으로 제한한다.
- GitHub Actions Workflow를 생성·수정하지 않는다.
- 자격 증명, 실제 회사 자료, 로컬 DB, 빌드 캐시를 커밋하지 않는다.
- 백엔드 검증: `bash scripts/maven.sh test`
- 프런트엔드 검증: `cd frontend && npm test && npm run build`
- 통합 빌드: `bash scripts/build.sh`
- 패키지 검증: `node scripts/smoke.mjs` (빌드된 JAR 필요, 임시 로컬 포트 사용)
- 기능 완료 후 `HANDOFF.md`, API/아키텍처 문서, 테스트 결과를 실제 상태로 갱신한다.
