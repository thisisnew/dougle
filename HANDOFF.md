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

## 0.5.2 — Independent style skill, hourly freshness, team knowledge README

User requested actual i-have-adhd skill invocation rather than only reference reads. Official Claude Skill tool supports invocation; upstream disable-model-invocation:true prevented it. Added independent skills/i-have-adhd and plugin copy, removed invocation prohibition (default enabled), preserved original rule body/license, added Dougle scope guard. Original immutable reference snapshot retained as attribution only. ask requests Claude Skill(dougle:i-have-adhd) or personal registered name, checks success, respects opt-out/denials. Codex uses its file-based skill activation; do not mislabel it as Claude Skill tool execution. If supplied catalog omits an entry, use discovery or verify installed sibling SKILL.md instead of declaring missing. No denial bypass.

Default freshness3600 seconds; explicit existing settings preserved, unknown-origin old stored default not silently overwritten. User also asked README to lead with connect -> internalize -> publish -> fetch; implemented and examples added. Active install docs now include all six skills, internal implementation prose not restored to README.

Validation: structural228, format6, manifests and isolated Claude plugin install. Native Claude attempt blocked by expired OAuth401 before any Skill event, not a pass; no auth changes. Native Codex3 completed synthetic runs: first2 omitted companion from supplied catalog, third after discovery fallback loaded actual installed companion and accurately disclosed file-based activation. Native skills/list independently reports companion enabled=true, errors=[]. Six freshness decisions pass (3599/3600 boundary, explicit86400, liveDB, failed attempt, unknown old default). No company knowledge read/written. Sanitized messages/commands in docs/skills/activation-verification.json. Existing external model completions181+3=184/200; separate failed Claude attempt1, no Claude model completion. Prior0.5.1 host delegated evaluations2 remain separate.

Both local host skill directories updated with backups in .tools/skill-backups/0.5.2-*. Existing extra user files retained. New6-skill ZIP44 files; package/public-release JSON authoritative. Actual Claude end-to-end invocation remains unverified until working authentication; no guarantee claimed.

README diagram follow-up: user requested diagram-design, then explicitly chose existing Dougle white/blue style. Installed cathrynlavery/diagram-design at f24d98b05e2d7a5e68c2dedfe46e5b7173f50118 in ~/.agents/skills. Read SKILL and relevant architecture/flowchart/export/style rules, used self_check and export_svg helpers. docs/diagrams includes two self-contained HTML sources + SVG exports + style guide + MIT notice. README embeds knowledge-sharing and ask-flow diagrams, retains exact update commands in details and moves lengthy examples behind summaries. Ego-browser space8 visual review: no text beyond viewBox, arrows readable; mobile page width390, local diagram scrolling; finished space. Skill installation available next turn. Product users do not need diagram-design installed.

0.5.2 release published, source2dd507a77c0cdf6672cf8657a024021408c5149a. Anonymous ZIP/checksum downloads match local artifacts;44 files,111584 bytes. Final doc links294 and both SVG accessibility/no-external-resource checks pass. Claude invocation remains BLOCKED_AUTH, not verified.

## Korean knowledge-sharing diagram revision

User approved visual-explainer alternative. Installed0.15.0 in ~/.agents/skills/visual-explainer via skill-installer and read actual instructions/style/kit. Rebuilt sharing diagram as three stages with Korean typography, white/blue styling. Source.src.html and rendered.html retained; README/how-it-works use fixed-font2x PNG. Skill renderer390/1280 and light/dark sheets checked; PNG visually inspected. Developer-only playwright-core installed inside the skill, not product runtime. Existing ask-flow untouched. Documentation-only change, no skill release bump.

## Remaining current diagrams unified

User requested replacement of remaining diagram-design/other diagram images. Rebuilt ask-flow and replaced active skills architecture/how-it-works Mermaid with knowledge-memory/skill-execution figures. All4 current diagrams now visual-explainer HTML + fixed-font PNG. Removed old ask SVG and unused diagram-design template license; historical app Mermaid and UI evidence screenshots intentionally retained as historical records. Docs-only update.

## 0.5.3 companion invocation visibility

User clarified i-have-adhd must install with Dougle and activate automatically during ask, not be an extra user command. Bundled upstream-based companion retained; user-invocable:false added for Claude menu hiding while model invocation remains enabled. Description/direct-invocation docs corrected. Codex hiding not claimed. Structural229 and Claude manifest validation pass; actual Claude invocation remains unverified due prior authentication failure.

## 0.6.0 dougle-schedule

User authorized implementation using existing Slack MCP, dedicated session, default5min/explicit interval, inquiry channel all human root posts, exact local approval before reply, final by dougle. No separate Slack app installation or live posting authorized in this development turn. Implemented Markdown-only skill with host timer discovery, one-owner/serialization, bounded paginated history and thread recovery, independent inquiry queue, exact draft/account-bound approval, consumed send intent and ambiguous delivery hold, pause/resume/interval/status controls. No daemon, automatic universal terminal opener or unavailable timer claimed. VDI-only DB needs user lookup with permitted minimal findings; no bypass or raw rows. Common organization allows only this explicitly approved Slack exception; schedule operational state not shared.

Independent host review found5 protocol gaps, patched. Native Codex2 synthetic evaluations30+5 cases, all expected decisions manually matched, only file-read commands, no gold reads/live services. External completed calls184→186/200; prior separate host evaluations preserved. Generic validator passes6 business skills; companion official user-invocable field unsupported by generic validator, not a product failure; structural245 plus official Claude manifests pass. See schedule-evaluation. Real timer/Slack posting/VDI E2E unverified.

User asked about ASCII banner token cost while work ongoing. Recommended one-time session banner + short draft heading, Slack by dougle unchanged. No banner behavior implemented yet; user has not chosen that proposal.

Signature steering: user rejected plain by dougle as visually weak. Changed current schedule signature to standalone **✦ dougle** (Slack mrkdwn *✦ dougle*, plain fallback). Approval includes the final signature. Previous synthetic decision evaluation used by dougle; behavioral approval/pagination results predate this presentation-only change. No large banner.

User extended compact signature to ALL Dougle-authored Slack comments, not only schedule. Added common organization rule in all five existing business skills and schedule explicitly loads common rule. Formatting does not grant posting permission; unrelated host actions cannot be globally enforced.

0.6.0 published at v0.6.0-skills-preview.1, source 3925a060a17ac3461990f507a15f0510a14f1785. Anonymous ZIP/checksum downloads byte-match local package;47files123763bytes. Local Codex/Claude copies updated with backups. Native company Slack/timer/posting/VDI still unverified.

## 0.6.1 schedule lifecycle management

User requested persisted local list/history/restore after terminal updates; interrupted work resumed via 다시해. Added management.md with schedule navigation discovery, immutable unique intent/result events, desired vs observed timer status, no implicit list-triggered start, explicit stop and restore rules, partial backlog continuity and retained queue. Old0.6.0 records supported without inventing past history. Independent review found unfinished stop intent and missing operation-to-timer binding; patched all-lifecycle reconciliation plus registered generation/callback verification and blocked ambiguous recovery. User knowledge not touched.

Native Codex1 synthetic12-case management run manually matched expected actions, no real services/store actions; external completed models186→187/200. Separate independent review covers two added crash cases. Structural250; business skill format and manifests checked. Real timer/state persistence/Slack company E2E unverified.

0.6.1 published at v0.6.1-skills-preview.1, source f9f5b29cb797b38c1935f7b97ff39f8e154f0153. Anonymous ZIP/checksum match locally verified assets;48files128265bytes. Local schedule skill copies refreshed with backups. Independent final review confirmed two crash fixes at instruction level. Real timer/Slack/state recovery E2E remains unverified.

## 0.6.2 conversational menu and direct Slack URL

User wants bare invocation menu1list2register3delete0exit, direct URL5min registration. Added menu.md plus routing and per-row management. Existing direct commands retained; timer checks never enter UI. URL is Slack channel URL, not arbitrary web crawler; message links require scope clarification. Existing channel preserves interval/identity, deleted record not implicitly reactivated. Delete stops verified owned timer then archives with history/unknown receipts retained; failures stay deletion_pending visible and old callbacks blocked. Independent review identified delete-intent approval gap; positive active state and no unfinished stop/pause/delete gating added before accepting or sending.

Native Codex1 synthetic12-case menu run matched expected decisions (external completed187→188/200). No actual user-store/Slack/timer actions. Structural255, format schedule and manifests pass; real host menu/tool/timer runtime unverified.

0.6.2 published at v0.6.2-skills-preview.1, source d09d5d4171772c46fbd7aa175c6a3ac680a7c482. Anonymous ZIP/checksum byte-match;49files132143bytes. Local schedule copies updated/backed up. Independent final review confirmed deletion approval gate patch. Real Slack/timer E2E remains unverified.

## 0.6.3 two-minute default

User requests new schedule default2minutes and asks about load. Updated canonical/plugin schedule entry/menu/management/host cron example to2minute /loop2m and */2. Existing saved cadences not migrated; user memory untouched. Documented nominal30vs12checks/hour, per-tick host AI cost even when no ask call, incremental paging/unchanged-draft avoidance, rate-limit backoff and host delay. No new native evaluation required for default/presentation change; earlier synthetic tests preserved with original5minute expectations as historical evidence. Structural/format/manifests/package checked; no real load measurement or actual Slack/timer E2E. External completed model ledger unchanged188/200.

0.6.3 published at v0.6.3-skills-preview.1, source 29ecd8b76458e9f57408d50361cf7692bee099bd. Anonymous ZIP/checksum match;49files132547bytes. Local schedule copies updated/backed up. Actual load/tokens/runtime not measured.

## 0.7.0 topic navigation and compatible partial retrieval

User authorized implementation after rejecting embeddings/engines. Added compact cards, stable section/claim maps, source/condition/dependency reads, navigation dirty/coverage reconciliation, legacy/full-note/no-write fallback and claim-local patches. Required dependencies preserved through scoped publish/fetch; do not broaden scope. Formatting never advances freshness; no real user store touched/migrated. Independent review found stale-valid-map exception risk and scoped-share dependency loss; both addressed, template completeness marker added.

Native evaluation11completed runs: initial/final12question baseline/candidate,3actual synthetic update runs,4focused reads (2candidate tuning variants); ledger188→199/200. Both final12question answers manually matched rubric; actual update fixture assertions pass source/unrelated unchanged, guard additions, IDs/correction/conflict preservation. Initial candidate increased input. Final batch157805→160576 input: no savings; focused last179829→171334 input,845→967output, cache/run variance forbids universal cost claim. Full synthetic artifacts, commands and model usage retained, no reasoning traces. Last concise path-only discovery change evaluated on focused question; full regression answer set predates that final wording and is disclosed.

Next authorized task in SAME user objective after this release: strengthen publish to BLOCK any staged token/password/PII and fail closed on incomplete scan. User explicitly requested it after retrieval. Do not stop after reporting retrieval; implement and test scanner/gate, maintain no new Python/Node user dependency.

## 0.7.1 publish sensitive-data blocking

Second explicit user task after retrieval: block publishing tokens/passwords/PII. Added bundled standard-OS sh/awk preflight as sole executable exception to instruction-only packaging; no new Python/Node user requirement. Whole final selected Markdown package and relative paths scanned; known patterns plus mandatory full semantic content/path review. Any candidate/error/partial/missing tools blocks ENTIREattempt, private targets too; no auto-redact-and-continue/bypass. Receipts hash filenames/content, detect changed snapshots, output only opaque file IDs/line/rule/count, never contents/paths; checks local outside package. Final changed merge invalidates review.

Independent review found5issues; fixed producer failure (od/hash/enumeration), all occurrences per line, filename scanning, mktemp stderr suppression, and single clean commit directly atop verified remote base with inspected tree/no extra outgoing history.60actual synthetic shell cases pass, including tool faults. No actual user memory, company sources or remote knowledge posts. Publish host/upload integration and universal secret detection not guaranteed; workflow is not network-level DLP. External model CLI ledger remains199/200; review uses separate host agent.

0.7.1 published at v0.7.1-skills-preview.1, source 21ba484372126947d588c427b5e8ff382ad2fbd1. Public ZIP/checksum byte-match;51files147726bytes. Canonical/plugin/local copies agree,60scanner synthetic cases and independent mutation checks pass. No actual knowledge uploads. Original0.7.0 also released and public assets verified. User requested tasks both complete; runtime company integration/DLP universal coverage still not claimed.

## 0.7.2 consistent respectful Korean

User reported inconsistent tone and forbids 반말. Added common 존댓말 rule for every Korean explanation, question, acknowledgement, failure and Slack draft/reply; casual user/source/imitate prompts never change Dougle narration. Clearly delimited quotes/code preserved, other languages professional. Companion formatting opt-out/failure does not disable tone. Applied common core to5skills, ask explicit entry/pre-send, companion scope, schedule all output. Canonical/plugin and14local installed skill directories updated with modification checks/backups; host-only metadata preserved. No user memory accessed.

Structural264,6business format checks, both Claude manifests and ZIP51file byte identity pass. Added8tone regression scenarios as criteria only: no new model-output evaluation, no universal output filter claimed. Prior native external CLI calls remain199/200. Publish scanner unchanged; previous60synthetic cases remain historical evidence. Version0.7.2 release authorized by standing user instruction; see package/public-release records for publication status.
