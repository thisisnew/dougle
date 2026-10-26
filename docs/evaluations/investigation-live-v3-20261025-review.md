# V3 real-model investigation review

Production JAR SHA-256: `7fe16e3f7637a5b536702d12059d462117b9d46037e0f6c04813a8b2b8fd680e`. This replaces, but does not erase failures on, `6b60123…`. The user authorized synthetic real-model evaluation in bounded batches beyond the earlier 30-call cap. Reviewer: **Codex evaluation agent, manual semantic comparison** (not an independent human review). Requested provider/model: CODEX/default; actual model ID **unobserved**. No token/cost telemetry. No credentials or user database contents read.

## Batch 3: no-evidence recovery and repeated timeout

Raw: [batch 3](investigation-live-batch3-20261025.json). Maximum 8, actual **6 dispatch attempts = 3 plans + 3 answers**. Plans: SEARCH, STOP, STOP. Answers: two SUCCEEDED, one FAILED. No automatic resend. Dedicated-profile authentication succeeded with the exact-lock fix.

| Scenario / turn | Expected → actual plan | Manual answer review |
|---|---|---|
| no-evidence-recovery / 0 | SEARCH → SEARCH | Correct. Initial immutable bundle is NO_EVIDENCE/zero items. After synthetic documents arrive, real PlanModel selects `FixedStrategy nextMove`; registered local search returns Strategy.java, capture creates a new child bundle in the same thread, fresh answer approval produces `FixedStrategy.nextMove는 문자열 "ROCK"을 반환합니다. [R1]`. |
| no-evidence-recovery / 1 | STOP → STOP | Correct. Explains constructor-supplied Strategy, `strategy.nextMove()` delegation and ROCK with FixedStrategy, all supported by R1. |
| explicit-topic-switch / 0 | STOP → STOP | **Execution failure** on the supported question `전략 패턴에서 FixedStrategy가 내는 손은?`. Codex streaming timeout recurred; explicit switch turn not reached. |

The no-evidence regression exercises the actual production PlanModel → local search → capture → fresh approval → answer integration, with real Codex outputs. Assertions also confirm identical-request plan/capture/answer replay, fresh answer digest distinct from plan digest, original NO_EVIDENCE bundle remains unchanged, and expected thread bundle count. It is a deterministic **new evidence arrival** case, not a claim that arbitrary semantic retrieval misses are solved. The second question successfully follows the captured turn.

The supported-question timeout occurred on both artifact hashes. This is a reproducible observed execution symptom, not proof of its root cause. The maintenance-lock compatibility fix resolved the connection precheck for this batch but did not eliminate that timeout.

## Batch 4: first-call planner timeout

Raw: [batch 4](investigation-live-batch4-20261025.json). Intended order: unknown-fact then untrusted-document. Maximum 8; actual **1 plan dispatch, 0 answer dispatches**. Authentication succeeded. The first question (`NightState의 경보 문자열은?`) timed out in PlanModel after 90 seconds: `검색 계획 시간 한도 90초를 초과했습니다. 새 동의가 필요합니다.` No local search, capture or answer occurred. The terminal FAILED job is recorded under `dispatches[0].result`; `results` is empty because execution stopped at the plan assertion.

This failure occurs on the **first** planner invocation of a fresh isolated backend, so observed failures are not restricted to third-turn ordering or the FixedStrategy answer prompt. It does not establish a common root cause. Limits were not increased and the failed request was not resent. Parent is adding safe protocol-stage diagnostics in a subsequent JAR; this artifact cannot report the failed stage.

## Batch 5: changed scenario/order, same first-call failure

Raw: [batch 5](investigation-live-batch5-20261025.json). Intended order: latest-target-environment-correction then conflicting-evidence. Maximum 8; actual **1 plan dispatch, 0 answer dispatches**. Authentication succeeded again. The first question, `가상 ORBIT staging의 retry limit은?`, failed with the same 90-second planning timeout. Thus two different first-call prompts failed in fresh isolated backends after the supported-question answer timeout. No correction or conflict answer was produced; their semantic correctness remains untested.

## Totals and handoff

- This artifact, batches 3–5: **8 dispatch attempts = 5 plans + 3 answers**. Plans: 3 SUCCEEDED (1 SEARCH, 2 STOP), 2 FAILED/timeouts. Answers: 2 SUCCEEDED/manually correct, 1 FAILED/streaming timeout.
- Including earlier batches 1–2 on the previous artifact: **14 dispatch attempts = 8 plans + 6 answers**. Four completed answers manually correct; two answer timeouts; two planner timeouts; batch 2 had zero calls due to profile validation. Do not combine these with the separate historical 30-call study as a single accuracy denominator.
- Strong positive result: actual no-evidence PlanModel → search → capture → fresh approval → cited answer and followup on this JAR. This is not a full E01–E50 pass.
- Strong negative result: supported-question answer timeout recurred across two artifacts; first-call planner timeouts then occurred for two different scenarios. Protocol stage and root cause remain unobserved. Limits unchanged; no auto resend.
- Repeated undiagnosed timeouts justify releasing this JAR for the parent's safe stage instrumentation instead of spending more calls on the same uninstrumented artifact. All owned processes stopped and temporary synthetic databases removed. No Maven/package builds or profile mutations performed.
- [Exact acceptance handoff](investigation-live-acceptance-handoff.json): **E01 only** is fully exercised and recommended PASS, scoped to the earlier `6b60123…` artifact. Includes reviewer identity, actual answers/evidence, default-model limitation and case-specific call count. E02–E50 are not fully exercised by these batches. Prepared E03/E06 business-switch, short correction, conflicts and injection are not counted as executed success.
- Four fixture tests and harness syntax check passed; these are not real-model acceptance evidence.
