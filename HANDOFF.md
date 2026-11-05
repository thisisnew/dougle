# Dougle 0.3.0 — 지침 전용 스킬

사용자 피드백으로0.2.0의 Python/SQLite 실행기를 제거했다. 자료 정리·업무 기억·중복 처리 절차를 스킬에 포함하고 호스트의 기존 도구를 사용한다. 서비스 종류는 제한하지 않으며 도구/로그인/권한/자료 부재/일시 오류를 구분해 안내한다. 설정 후 pending 요청을 재개한다.

정본 skills/, 플러그인 plugins/dougle/, Claude marketplace .claude-plugin/marketplace.json. 공통 organization.md에 작업 공간/자료 동일성/범위/사실·진술·추론/갱신·정정·충돌·동시성 한계가 있다. 새 기억은 ~/.dougle/workspaces의 Markdown이며 이전 SQLite는 자동 이전하지 않는다.

과거 실행기/설치기/테스트를 제거했고 증거는 docs/skills/history-0.2.0에 보존했다. 이전35+7 도구 테스트는 새 스킬의 의미 품질 근거가 아니다. 새 구조/설치 검사 결과와 미검증 범위는 docs/skills/verification.md를 따른다.

사용자가 설치 스킬만으로 쓰길 원하므로 Python/Node/Java 런타임을 제품에 재도입하지 않는다. 개발용 검증 도구와 사용자 실행 의존성을 구분한다. 회사 데이터/원본은 읽기 전용, 호스트의 전역 권한 강제는 제공하지 않는다.

## 공개 완료

v0.3.0-skills-preview.1 공개. 공개 ZIP과 체크섬 파일을 익명 다운로드해 검증한 로컬 파일과 바이트 일치를 확인했다. 현재 PC의 개인 Codex/Claude 스킬도 Markdown 전용으로 교체했다. 회사 접근/모델 의미 품질은 미검증이다. docs/skills/public-release.json 참고.

## 0.3.1 — 명시적 reload

dougle-reload 추가. 기존 연결만 대상으로 변경/동일/기준 없는 재확인/부분/접근실패/삭제확인/미처리를 구분한다. 마지막 시도와 마지막 성공 시점 분리, 실패 시 구식 근거의 현재 사용 방지, 영향받는 주장만 갱신, 사용자 정정 보존, 중단/재개 지침 포함. 전용 실행기 없이 호스트가 수행한다. 현재 문서는0.3.1 기준, 이전 검증 기록은 docs/skills/history-0.3.0에 보존한다.

0.3.1 공개 완료. 구조35항목/형식3개/Claude 격리 설치·활성 확인, ZIP9파일과 공개 자산 바이트 일치 확인. 개인 Codex/Claude 세 스킬도 갱신했다. 실제 모델의 의미 평가와 회사 접근은 미검증.

## 0.4.0 — 지식 publish/fetch

dougle-publish와 dougle-fetch 추가. 저장소/공개범위/실제 내보내기 범위를 확인한 뒤 정제 지식만 게시한다. fetch는 고정 커밋을 읽어 로컬·원격·기준점의 주장/근거를 병합하고 정정·원격 전용 추가·충돌을 보존한다. 원격 삭제로 로컬 지식을 삭제하지 않는다. 출처 ID 네임스페이스/개인 경로 매핑/재실행 멱등/중단 체크포인트/업로드 경합 처리 포함. 호스트 도구로 수행하는 절차이며 전용 실행기는 없다. 실제 공유 저장소는 아직 사용자가 지정하지 않았고 회사 지식 업로드는 하지 않았다.

0.4.0 공개 완료. 구조66항목/스킬 형식5개/Claude 격리 설치·활성 확인, ZIP15파일과 공개 다운로드 일치 확인. 현재 PC 개인 스킬도 갱신. 실제 지식 게시0건, 회사 접근/실모델 의미 병합 검증은 미실행.

## 세션 재개 메모 — 2026-11-04

### 지금의 제품과 사용자 요청

현재 제품은 앱/터미널 UI가 아니라 Claude Code·Codex에서 실행하는 다섯 스킬이다. connect/ask/reload/publish/fetch. 설치 의존성이나 별도 서버를 도입하지 않는다. 원본 업무 시스템은 읽기 전용. 정제 지식 publish만 사용자가 지정한 저장소와 범위에 게시한다. fetch는 기존 지식을 덮어쓰지 않고 정정·근거·충돌을 보존해 병합한다.

사용자는 완료한 작업을 GitHub에 반영하길 원한다. 구현 변경은 검증 후 릴리스, 이번 문서만 변경한 작업은 main에 반영했고 새 기능 릴리스는 만들지 않았다. 현재 공개 스킬 릴리스는 v0.4.0-skills-preview.1이다. 이전 데스크톱 요구를 현재 구현 대상으로 되살리지 않는다.

### 방금 완료한 일

사용자가 https://github.com/ayghri/i-have-adhd 스킬 적용을 요청했다. 원격 skills/i-have-adhd/SKILL.md를 읽고 적용했다(이 스킬을 로컬에 설치한 것은 아니다). 응답은 행동/답부터, 짧은 번호 단계, 불필요한 서론 없이 작성한다. 사용자가 해제하기 전까지 적용한다.

README와 현재 docs의 안내 11개를 설치→자료 연결→질문 순서, 복사 가능한 명령, 접힌 상세 설명으로 개편했다. 커밋 4a36ac1을 main에 push했다. 내부 링크43개·코드 블록·스킬명·브랜딩과 구조 검사66항목 통과. GitHub README blob과 로컬 hash 일치 확인. 실제 회사 연결/AI 의미 품질 검증으로 과장하지 않는다.

### 아직 해결하지 못한 사용자 제보

회사 PC의 Claude에서 사용자가 'reload-skills'로 스킬을 재로딩했지만 dougle-fetch 등이 선택지에 나타나지 않는다고 했다. 사용자는 dougle-reload(자료 갱신)를 말한 것이 아니다. 앞선 응답에서 혼동했으므로 반복하지 말 것.

회사 PC의 실제 설치 상태는 보지 못했다. 재로딩과 설치 파일 업데이트는 구분하되, 해당 명령의 정확한 버전별 동작은 확인하지 않았으므로 단정하지 않는다. 원인 후보: 이전 스킬만 설치됨, 업데이트 경로와 활성 설치 경로 불일치, 중복 개인/플러그인 설치나 캐시/인식 문제. 어떤 것도 확정되지 않았다. 회사 PC에서 활성 설치 경로/버전/dougle-fetch/SKILL.md 존재 여부를 확인하고, 없으면 같은 설치 방식으로 업데이트, 있으면 인식 실패를 진단해야 한다. ~/.dougle/의 사용자 지식을 삭제하거나 변경하지 않는다. 이 PC의 정상 설치로 회사 PC 문제가 해결됐다고 주장하지 않는다.

Claude 플러그인 명령은 /dougle:dougle-fetch, 개인 폴더 설치는 /dougle-fetch, Codex는 $dougle-fetch다. 설치 및 최신 안내는 docs/skills/install.md를 확인한다.

### 재개 방법

사용자가 cmux 업데이트 전에 대화 유실을 걱정해서 이 메모를 작성했다. 전체 대화 원문을 백업한 것은 아니며 핵심 결정·작업 상태·미해결 항목을 저장했다. cmux 업데이트가 실제로 대화를 삭제하는지 또는 이 환경의 대화 복원 방법은 확인하지 않았다. 자동 복원을 보장하지 말 것.

새 세션에서는 이 파일과 AGENTS.md를 읽고 git status를 확인한다. 사용자의 다음 지시를 받아 이어간다. 현재 미해결인 회사 PC 스킬 선택지 문제는 원격 설치 상태 확인 없이는 해결 완료로 처리하지 않는다.

## 0.4.1 — ask 답변 가독성

사용자가 회사 PC의 새 스킬 미노출 문제는 해결됐다고 확인했다. 더 이상 미해결로 취급하지 않는다. 새 요청은 ask의 장황한 답변 개선. 외부 스킬 설치 없이 ask에 결론 우선·짧은 기본 답변·주장 옆 근거·후속 질문 반복 생략·상세 요청 존중 지침을 내장했다. 사실/추론/정정/불확실성/최신성 및 읽기 전용 규칙은 유지한다. i-have-adhd 소스를 복제하거나 진단을 가정하지 않고 더글 답변 범위에 맞춘 자체 지침을 작성했다. 실제 모델 가독성 평가는 별도로 실시하지 않았으며 구조 검사로 품질을 보장하지 않는다.

## 내재화 방식 문서 — 2026-11-05

사용자가 내재화가 그래프 방식인지 질문하고 문서화를 요청했다. README와 architecture.md에 개념별 Markdown 외부 기억, 주장/관계별 출처, 목차→관련 주제→원본 재확인 흐름, 갱신/세션 지속성, 그래프 DB·GraphRAG·파인튜닝과의 차이를 명시했다. 새 그래프 엔진을 구현한 것은 아니다. 회사 스킬 미노출 문제는 사용자 확인으로 해결됨.

사용자는 README의 “더글용 Python·Node·서버는 필요 없습니다. 다른 설치 방법과 업데이트” 같은 불필요한 기술 설명을 지적했다. 해당 문장과 첫 사용 안내의 유사 문구를 삭제했다. 사용자 문서는 할 일·예시 중심으로, 기술 세부는 요청된 구조/개발 문서에 둔다.

0.4.1 프리뷰 공개 완료: v0.4.1-skills-preview.1, 소스 b1c185f036888a90f54e08e59f21e8e573728c8f. 공개 ZIP 익명 다운로드와 로컬 검증 ZIP 바이트 일치 확인(48,706 bytes). 이후 내재화 설명과 불필요한 첫 사용 문구 제거는 문서 전용 main 변경이다.

## 0.4.2 — ask 실행 시 기한 기반 갱신

사용자가 예약 실행 대신 ask 시 마지막 갱신 성공 시각과 비교해 기한 경과 시 갱신 후 답변하도록 요청했다. 기본24시간, 자료별/작업공간별 자연어 주기, 실제 확인 범위별 성공·시도 시각, 최신 상태 질문은 항상 조회, 실패 성공시각 불변, 부분 처리·구기록·미래시각·가져온 주장 재확인, 로컬 주기 설정 공유 제외를 추가했다. 공통 프로토콜과 다섯 스킬/배포 사본을 맞췄다. 문서는 README에 직접 동작과 주기 예시를 적었다. 모델 품질 검증과 구조 검사는 구분한다.

0.4.2 공개 완료: v0.4.2-skills-preview.1, 소스49cdcedadf8333db57469f9c6c8987ab33e3b1ee. 구조66항목·형식5개·Claude 매니페스트·문서링크50개·ZIP15파일 검증. 공개 ZIP(60,467 bytes)과 체크섬을 익명 다운로드해 로컬 자산과 일치 확인. 실제 모델 기반 갱신 시나리오 검증은 미실행.

## 0.4.3 — 검색 절차·가독성 원본 내장·출처

사용자가 속도/Lucene 트레이드오프, i-have-adhd 기본 적용, 답변 출처를 요청했다. Lucene은 도입하지 않고 주제·동의어 목차, 정확한 원본 위치, 독립 읽기 묶기, 부족 시 검색 확대를 추가했다. 실제 지연 개선은 미측정. ask는 원본 i-have-adhd(1ecea1993b7fce257ffbd686c070948b5e07eda6)와 MIT 라이선스를 references에 포함하고 답변 전에 읽는다. 독립 스킬 자동 발견에 의존하지 않으며, 문서에서는 독립 명령이 자동 등록된다고 주장하지 않는다. 주장별 원본 출처/코드 위치/DB 조회시각, 간접근거·추론 구분을 강화했다.

사용자가 i-have-adhd 실제 설치와 그 지침에 따른 README 개편을 재차 명시했다. skill-installer로 현재 PC의 ~/.agents/skills/i-have-adhd 및 ~/.claude/skills/i-have-adhd에 고정 커밋을 설치하고 LICENSE.md도 보존했다. 원본 파일 일치 확인. 사용자 다음 턴부터 Codex 스킬로 사용 가능하다고 안내했다. README는 설치→연결→질문, 가상 출처 예시, 모델별 실제 업데이트 명령과 문제 대처를 유지해 정리했다.

0.4.3 공개 완료: v0.4.3-skills-preview.1, 소스9837e88e45c55cbdaf91c37846550e06d5a5a1f2. 구조76·형식5·문서링크58·격리 Claude 설치/활성·번들 지침/라이선스 바이트 확인. ZIP17파일(71,236 bytes)과 체크섬 익명 다운로드 일치 확인. 실제 모델 가독성/출처 준수, 검색 지연 개선은 미측정.

## 0.5.0 — 디렉토리 독립 공통 지식 저장소

사용자는 작업 공간 선택도 원하지 않으며 사용자 PC의 공통 디렉토리에 지식을 모아 어디서 실행해도 찾기를 원한다. 기본 ~/.dougle/index.md + sources/knowledge/sharing으로 변경. 기존 workspaces/<label>/workspace.md는 공통 목차에 포인터로 연결하고 파일·상대근거·ID·설정·공유바인딩은 보존한다. cwd는 명시적 상대경로/현재프로젝트 요청에만 사용. 프로젝트/서비스/테넌트는 메타데이터로 구분하고 동명이인은 무조건 병합하지 않는다. reload 전체는 등록된 공통·기존자료 전체, 기존 pending 작업은 원래 범위 유지. publish는 선택한 범위만 게시하며 새 공통저장소 전체로 확장하지 않는다. 사용자 지식 데이터 자체를 이번 개발 중 이동/마이그레이션했다고 주장하지 않는다.

0.5.0 공개 완료: v0.5.0-skills-preview.1, 소스c12fad3185e3d27880ce46aa2d97e0751b21ed53. 구조76·형식5·문서링크75·격리 Claude 설치/활성 확인. ZIP17파일(79,364 bytes)과 체크섬 익명 다운로드 일치 확인. 현재 PC의 ~/.agents/skills 및 ~/.claude/skills 더글5개를 0.5.0 정본으로 갱신하고 바이트 일치 확인. 이전 설치는 .tools/skill-backups/0.5.0-20261105-182820에 보존(ignored). 실제 사용자 기억은 건드리지 않았다. 실제 모델의 교차 디렉토리·기존 지식 발견/갱신 평가는 미실행.

## 0.5.1 — Context optimization and execution documentation

Applied requested conditional instructions, reuse of unchanged active-context evidence, and passage-first retrieval with mandatory expansion. Installed context-optimization in .agents and .claude development skills; it is not a per-answer runtime dependency. Split protocol into core plus store/retrieval/records/freshness/operations, retaining all six original detailed section groups. i-have-adhd original and license preserved. Canonical, packaged and both local installed skill copies updated with backups; no real knowledge changed.

Two independent host-agent evaluations, same six synthetic questions per version, gold withheld: baseline 6/6 and candidate 6/6 against manual rubric review. Loaded instruction files 42779 -> 36733 bytes. Token billing and latency not measured. No claim of all-model/company accuracy or persistence/merge verification. Fixtures, answers and read logs in docs/skills/token-evaluation; gold/results beside it. Candidate absent legacy-directory lookup error disclosed, original reads and answers completed. Final core EOF whitespace cleanup only after evaluation. External CLI model-call ledger remains181/200; host delegated runs2 separately recorded.

Added docs/skills/how-it-works.md linked from README and docs index: invocation, source access, common memory, retrieval, freshness, selective instruction loading, sharing and permissions. Structural212, format5, Claude manifests, isolated install and269 local doc links passed. Package/public-release records are authoritative for asset details.

0.5.1 published: v0.5.1-skills-preview.1, source4c7983ba629ddd14e4ff8576827048fece8223b1. Anonymous ZIP and checksum downloads match local assets; ZIP42 files,105610 bytes.
