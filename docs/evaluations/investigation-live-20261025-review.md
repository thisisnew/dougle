# Live investigation review — 2026-10-25

Artifact: production JAR SHA-256 `6b60123c3502153dea371118e1d9873242b431a4b9a6ff3d37978a2c0a81bc52`. Synthetic documents only, isolated temporary databases; no credential reads/copies. Requested model is provider default (`""`); actual model ID is **unobserved**. This is not a blanket acceptance PASS.

Machine reports preserve original observations and mark `manualVerdict: NOT_REVIEWED`; this separate document is the subsequent manual evidence/answer review. Plan dispatches and answer dispatches include accepted attempts but are not independent billing telemetry. Historical 30-call authorization has been superseded by the user's current authorization; every batch still has a finite maximum.

## Batch 1

Raw: [batch 1](investigation-live-batch1-20261025.json). Maximum 8; dispatched **6 = 3 plan + 3 answer**. Two answers succeeded; one failed. The batch stopped on failure, without automatic resend. No backend or owned process-group descendants remained after cleanup.

| Scenario / turn | Expected → actual plan | Manual answer review |
|---|---|---|
| strategy-followup / 0 | STOP → STOP | Correct. Explains Strategy.nextMove, FixedStrategy returning ROCK and Player delegation; all statements match cited R1. |
| strategy-followup / 1 | STOP → STOP | Correct. Emphasizes Player.play delegation and interface → implementation → caller reading order. Does not switch to NightState/Singleton despite those distractors in evidence. |
| explicit-topic-switch / 0 | STOP → STOP | **Execution failure**, not an answer-quality pass. Supported ROCK question failed with `Codex 스트리밍 제한 시간을 초과했습니다.`; total turn time 125,054 ms. Explicit switch turn was not reached. |

The streaming timeout is an observed runtime/provider failure; this evidence does not identify whether the root cause is provider latency, CLI transport or application timeout policy. No operational logs or credentials were inspected to speculate. Plans completed normally. This first batch ran before the harness added `originalEvidence`; since all its plans STOPped, its final `evidence` is also the original evidence.

## Batch 2 and blocking defect

Raw: [batch 2](investigation-live-batch2-20261025.json). Intended unknown-fact and injected-document scenarios, maximum 8; **0 dispatches**, **0 evaluated turns**. The backend's `/ai/providers/CODEX/connection` returned `CHECK_FAILED`, so the harness stopped without fallback, automatic login or AI submission.

A subsequent bounded `codex --config cli_auth_credentials_store="file" login status` probe against the same dedicated profile returned exit 0 and exactly `Logged in using ChatGPT` in 215 ms. Only boolean status/elapsed time/character count was printed; no authentication file was read or copied. A directory-entry/permission-only inspection found a regular `.sqlite-maintenance.lock` in the dedicated profile. `CodexProfile.validate()` permits specific filenames and numeric `state|logs|goals|memories|queue` SQLite names, but **does not permit `.sqlite-maintenance.lock`**. It therefore throws `Unexpected profile content`; `ProviderConnectionAdapter.check()` maps such exceptions to `CHECK_FAILED`.

This is a concrete profile compatibility blocker in the current artifact, not evidence that login expired. The exact causal relationship between the first answer timeout and creation of the maintenance lock is unproven. No profile file was deleted, permissions changed, validation bypassed or backend source edited by this evaluator. Backend owner was notified; further real-model runs pause until the supported profile validation path is repaired. This stop respects the safety boundary rather than masking the failure in the harness.

## Current totals and uncovered scope

- New evaluation total: **6 dispatch attempts (3 plan, 3 answer)**; plans 3 SUCCEEDED/STOP; answers 2 SUCCEEDED, 1 FAILED/stream timeout. Historical 30 calls are a separate evaluation, not included here.
- Manual answer review: **2 correct**, **1 execution failure**, no partial or incorrect completed answer observed in this small subset. No general accuracy claim.
- Implicit followup with distractors was exercised. Explicit topic switching stopped before its second turn. Latest target/environment correction, conflicting evidence, injection, and sufficient/missing decision comparison remain **NOT RUN**, despite fixture coverage.
- **No SEARCH → local search → capture → fresh approval → answer chain was reached.** Do not treat passing fake-CLI smoke or this batch's STOP answers as real integration coverage of that chain.
- Actual provider model ID remains unobserved; requested model is default. Costs and tokens unmeasured.
- Both owned backend process groups were cleaned up; temporary synthetic databases removed. The production JAR is **no longer in use** by this evaluator and may be rebuilt. No Maven/package builds were run.
- Harness syntax and four fixture tests passed. The next artifact needs a separately hashed no-evidence → SEARCH → capture regression in addition to the unreached matrix, because the parent reports implementing question-only plan preview and capture fixes after this artifact.
