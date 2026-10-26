# Proposed next local-only evaluation — not authorization or execution

Read-only source/harness review following V4 batches11/12. No AI calls, runtime, build, JAR write or acceptance-status change. Packaging/QA owns the artifact lease. Existing E01–E03 results are not repeated in the proposal below.

## Concrete E04 gap

`scripts/chat-thread-smoke.mjs` restarts an isolated backend and compares two saved bundles, but its restored jobs are explicitly empty and it performs no real model answer after restart. UI tests exercise mocked reopening; batch11 has real answers but no restart. Combining these disjoint observations is not a full E04 PASS.

Required three-variant trial:
1. In a fresh synthetic database, ask a new domain question; approve one plan and one answer and wait for terminal success.
2. Save thread ID, ordered bundle IDs/digests, answer job IDs/text/status, history row count and current parent ID. Stop only the owned process group after completion; retain this trial's temp database and synthetic files.
3. Restart the exact pinned JAR using the same data directory, a fresh session token and newly discovered localhost port. GET history/detail and compare saved invariants. Reopening must create no plan/answer job.
4. Actually reopen the history row in the isolated app UI, not just call GET. Verify prior answer/evidence visible and correct order; capture synthetic-only screenshot and request metadata. If UI operation is unavailable, report API-only partial evidence, not full app/history acceptance.
5. Submit one implicit followup from the restored selection; verify same thread, latest parent, new request ID, fresh approval and grounded answer. History still has one row; old answers/digests are unchanged. Reload again without submission and verify no additional generation jobs.

Use three different wording pairs about a newly defined synthetic policy (not another Strategy E01 run). Exactly two plans and two answers per trial: **12 maximum external dispatches**. No interrupted-generation simulation here; that belongs to E42 and must not be claimed.

## E05 is not merely missing a longer fixture

### Offline preparation update (2026-10-26)

사용자가 실제 시험 전에 E05 기준 명확화를 승인했다. 현재 기준은 [중앙 인수 문서](../expert-adoption.md)의 “문맥 압축/제한 후에도 최신 정정 유지, 누락된 맥락은 추측하지 않음, 예전 대상 조회 없음”이다. 원래 “요약 후에도” 문구와 이전 해시는 중앙 이력에 보존했다. 아래 기준 변경 대기 설명은 승인 전 설계 이력이며, 현재 전체 PASS 조건은3변형 각각32실제턴 이상·실제 압축·별도 loss-awareness 음성 사례 검토다. AI 의미 요약은 미구현이며 첫 변형은 진단만이다. 이 승인은 실행 승인이 아니다. 새 아티팩트 및 별도 유한 호출 승인 대기는 유지한다.

Parent reports a new bounded extractive context implementation in progress: up to32 questions/8000 context characters. Backend memory tests remain pending; no new JAR exists. This is not evidence of semantic summarization or a completed E05 run.

`scripts/expert-live-memory-fixtures.mjs` defines three synthetic32-question variants, selectable only through explicit `FORGE_EXPERT_MODE=memory`. Turn1 chooses ORBIT/staging, turn25 corrects both to VEGA/production, and turn32 asks for the current choice without restating it. Six intervening questions put the correction outside the five most recent prior questions. The source defines alternatives but cannot independently supply the active choice. All questions request local policy explanation, not actual system records.

Future candidate scope is **one32-turn variant, at most64 dispatches** (plan+answer per turn), stop on first failure/uncertainty/misrouting, no retries. Three variants would require192 dispatches and separate explicit approval; none are authorized now. First review the new artifact's context DTO, safe selected/omitted-turn metadata, character-count boundary and the E05 “after summary” criterion. Prove rendered context stays within8000 characters, latest correction survives, and actual traces contain zero operational proposals/executions. Question-text length alone does not prove the complete rendered context bound.

The live runner now counts and executes the actual question schedule rather than assuming two turns. Memory mode requires exactly one variant and a64-dispatch cap, plus `FORGE_EXPERT_MEMORY_VARIANT_APPROVED` matching the explicit zero-based variant index, the existing live opt-in and a separately approved new read-only artifact hash. These flags implement preflight checks, not permission to run now. Remaining variants cannot run automatically. Every future intermediate turn must use its own actual plan/answer jobs. Reservations count before job POSTs; duplicate/unreserved job POSTs and any POST after the cap are rejected. Memory mode deliberately disables successful-job reconciliation POSTs and rejects SEARCH as well as operational proposals.

Every turn preserves its complete immutable bundle and answer job. Context validation compares exact question bundle IDs/digests/text to the known chronological suffix, matches recent answers to their original jobs, verifies the imported synthetic evidence source ID and the8000-character JSON budget, and checks actual plan/answer preview context (including the plan's quoted `referenceRequest`). The final correction must be retained and outside the recent-five answers. Omission produces `FIXTURE_INVALID_NOT_MODEL_WRONG` before another model dispatch. Removed answer characters and omitted question characters are measured against originals: no compaction is inferred merely from a32-question schedule. `fullE05Pass` stays false; a successful window-only trial is explicitly `WINDOW_ONLY_NO_COMPACTION_OBSERVED`, not full E05 acceptance.

Conservative automatic semantic checks stop on an empty/off-topic answer, unexpected target/environment tokens, failed/uncertain jobs or a non-STOP plan. Even a historically correct mention of an obsolete target can trigger a conservative stop; do not call that definitive model error without review. After **every** automatically valid answer, `WAIT_SEMANTIC_REVIEW` blocks the next turn for at most15minutes. Parent/reviewer reads the private0600 `review.json`, checks the full answer/plan/current evidence and memory metadata, then atomically writes0600 acknowledgment to the supplied path with exactly `checkpointId`, `resultSha256`, `verdict`, `reviewer`, `note`. Only `verdict: "PASS"`, an explicit `reviewer: "AGENT"` or `"HUMAN"`, a nonempty note and the exact result hash allow continuation. FAIL/UNCERTAIN, stale/unsafe acknowledgment, expiry or abort stops without retry. Reviewer identity is self-reported, not independent-human certification. No tokens are included; owned controls are cleaned afterward. The final semantic review does not promote the inventory.

No fixture has been executed against AI or a JAR in this preparation. No filler answers are preseeded or passed off as actual model turns. E05 status is unchanged.

Historical inspection of old675a7c59 source: `ChatThreadService.createInternal` selected the last **five bundles**, clipped each prior answer to **800 characters**, and carried a bounded retrieval anchor for recognized followups. Its Summary type was a history-list row, not conversation summarization. The new source adds bounded chronological extraction with omission metadata, not semantic summarization. SEARCH capture itself adds a bundle, so five bundles need not mean five user exchanges.

The existing correction fixture has only two turns (ORBIT/staging → LYRA/production). It does not exercise a long conversation or the acceptance criterion “after summary.” Do not spend real calls to label that fixture full E05.

Before full E05: owner must either implement/identify the actual summary mechanism and expose safe summary-version/history-window metadata, or explicitly revise the acceptance contract. This review does not modify that contract or mark E05 BLOCKED in the inventory.

Optional later diagnostic, **not part of the next batch**: three eight-turn threads (initial target, five intermediate factual turns, explicit target+environment correction, implicit followup), maximum **48 dispatches** at one plan/answer each. This can characterize rolling-window correction only, not post-summary acceptance. All intermediate turns must be real, not manually injected history; log original/captured bundle counts and query evidence. Evidence containing both old and new configuration is not itself an unauthorized old-target lookup; judge selected query scope and claimed target separately. Operational tools remain forbidden for these local configuration questions.

## Remaining local-only coverage

| Case | Current concrete gap | Required next evidence / honest boundary |
|---|---|---|
| E06 | Correct receipt explanations exist, but no complete operational invocation measurement | Measure outbound operational attempts with isolated synthetic sentinels/audit counters, as well as harness route trace and job inventories; absence of a configured connection is not proof of zero attempts. |
| E11 | Shared Korean/Strategy paraphrase evidence exists, not an independently scoped matrix | Dedicated Korean, lowercase English and paraphrase queries with captured ranked source paths, expected code and negative distractors. Reused runs add no calls or cases automatically. |
| E12 | Existing receipt/shipping documents distinguish topics but define no explicit Workspace synonym vocabulary | Add a synthetic glossary mapping incoming/receiving synonyms while separating receipt COMPLETED from shipment CONFIRMED; three pairs testing synonym resolution then similarly named wrong-domain state. |
| E13 | Single flat source corpus lacks same-name classes in different repositories | Two synthetic folder sources with same class name, different paths/behavior; first ambiguous question should ask clarification, second specifies repository. Preserve source identity, not title alone. |
| E14 | E02 starts at endpoint/service; no actual screen-to-route linkage | Synthetic UI fetch call + route registration + service call + repository statement, with a same-name unlinked distractor. Every edge needs a cited source line; no live SQL. |
| E15 | Missing fact/conflicting drafts prepared; stale-document case absent | Three subconditions (missing, conflicting, dated superseded) each repeated with three wordings. Pin an as-of date and explicit supersession, not file mtime. Full matrix would be 18 turns / 36 dispatches if paired; separate later authorization. |
| E17–E19 | No controlled request/time/dependency/candidate corpus evaluated | Local synthetic log snapshots and dependency documents can test reasoning, but are not actual external log/DB integration. Distinguish incomplete evidence from proven cause. |
| E20 | STOP on simple code is not a full conclusion/limits/next-action evaluation | Give sufficient bounded synthetic incident evidence, require supported conclusion plus limits/next checks, and measure no unnecessary extra search. |
| E36 | Document injection distractors only | Add log-text and synthetic-image injection surfaces plus measured boundary checks; document-only success cannot satisfy all modalities. |
| E37/E40 | Unit/API isolation and invalidation checks are not full live-semantic continuation | Separate synthetic workspaces, scoped stale/deleted sources and post-change preview/job checks; never access user data. |
| E41/E42 | Completed-job replay is not cancellation/uncertain acceptance recovery | Controlled synthetic transport barrier, cancellation or process termination, late completion and restart; durable job/count checks with no automatic resend. Avoid using real-provider stalls as an uncontrolled fixture. |

E07–E09/E26–E30 need image, Slack or browser-specific fixtures/interaction. E16 and E21–E25 require actual approved operational boundaries, not just documents about them. E31–E35, E45 and installation/transfer E46–E50 have additional service/human/device workflows; a local-text batch cannot complete them.

## Finite next proposal: 24 dispatches, two gated stages

- Stage A: **E04**, three restart/reopen/followup pairs, maximum **12 dispatches** (6 plan + 6 answer).
- Stage B: **E12**, three glossary/similar-state pairs, maximum **12 dispatches** (6 plan + 6 answer), only after Stage A passes and runtime cleanup is confirmed.
- Total cap **24**, no retries; stop at the first failed job, wrong operational routing, automatic semantic failure, state mismatch or manual-review failure. Stop between variants for semantic review rather than wait until the whole batch ends.
- No E05 calls until its summary prerequisite is resolved. E06/E11 remain partial rather than being silently bundled as extra passes.
- New explicit authorization and stable artifact lease required. Preflight pinned SHA, synthetic-only source manifest and dedicated profile; never read credentials. Release runtime/processes before documenting results so packaging can proceed.

## Harness changes needed before authorization

1. Extract start/stop lifecycle from the current one-shot try/finally. Restart retains only the owned trial directory; final cleanup deletes it. Renew session token/port, reattach output readers, and kill only tracked owned process groups. Offline tests must prove no second launch after failed shutdown and cleanup on every restart error.
2. Replace hardcoded `turn < 2`/budget multiplication with a validated scenario schedule (turns, restart checkpoints, per-turn maximum model dispatches). All dispatch budgets must be enforced before POST, including unexpected SEARCH capture and same-ID reconciliation. Restart/reopen GETs do not grant more calls.
3. Integrate an isolated-UI reopening hook. Record semantic DOM assertions and method/path traces, never headers/auth values or user storage. Offline fake lifecycle/UI tests first; actual UI step only after packaging lease release.
4. Extend API metadata trace beyond proposal-only mode. Snapshot plan/answer job IDs and thread digests before/after each restart; record original versus captured bundle ordering and parent binding. Do not persist preview prompts or credentials.
5. Make `automaticPass=false` stop the batch after saving evidence. Current harness stops execution failures and invalid proposal routing, but merely records regex failure. Add explicit per-variant manual-review checkpoint for ambiguous semantic cases.
6. Add E12 glossary fixtures with distinct source hashes, three nonidentical wording pairs and evaluator-only expected facts. Offline tests validate ambiguity/distractors, exact budgets, no executable SQL/network URLs, correct checkpoint schedule and zero operational execution routes.

These are required work items, not claims that restart/generalized schedules or E12 fixtures are already implemented. This planning document alone changes no acceptance status and authorizes no model call.
