# Batch 8 — bounded E01 diagnostic trial

- Artifact SHA-256: `276e56fd38d1720cb0bde17072bd2b14ab63233d7fb6e356e82d32bd782b6f22`.
- [Raw report, full answers and evidence](investigation-live-batch8-diagnostics-20261026.json).
- Reviewer: Codex evaluation agent, manual comparison against captured synthetic evidence; not independent human review.
- Scope: fresh isolated application/database, existing dedicated CLI profile, one E01 variant with two actual consecutive turns. No credentials read, automatic retries, Maven or JAR writes.
- Actual dispatches: **4/4 maximum: 2 plans + 2 answers**, all SUCCEEDED. Both plans expected STOP and returned STOP. No SEARCH/capture branch exercised.
- Requested provider/model: CODEX / empty requestedModel (default). Actual model ID remains **unobserved**.

| Turn | Exact question | Expected and actual | Manual verdict |
|---|---|---|---|
| 0 | 전략 패턴 예제 설명해줘 | Explain Strategy.nextMove, FixedStrategy returning ROCK, and Player delegation. Answer cites R1 and states precisely those relationships. | Correct for captured evidence. |
| 1 | 어느쪽을 중점적으로 보면돼? | Preserve Strategy topic and identify the important delegation point. Answer emphasizes Player.play → strategy.nextMove and constructor injection, cites R1, and does not switch to Singleton, NightState, receipt or shipping distractors. | Correct for captured evidence. |

Both turns use thread `08e75074-011f-4b38-82a4-771f9aa80b1e`; recorded historyTurns is 0 then 1. End-to-end turn latencies are 12,119 ms and 10,791 ms, including planning/retrieval/answer work.

| Turn | Plan request ID | Answer request ID |
|---|---|---|
| 0 | `2cf4fd6c-3796-4e3c-8f55-0a81ae20429f` | `4502e7be-df39-44b4-aaf7-89febcee5751` |
| 1 | `8645d32b-cdd5-4740-bc85-cbf3b0e6ac9d` | `877669e4-14a6-42e3-a929-f9ec54b7c07c` |

## Diagnostic and acceptance limits

The report records CODEX_EXEC and CODEX_APP_SERVER with outcome SUCCEEDED and terminal phase COMPLETED. That phase is inferred by the harness from successful job status, not a sampled internal protocol event. New typed failure diagnostics were **not triggered**: stdout/stderr byte counts, stdin-written flag and stderr signal are **unobserved**, not zero/NONE. No failure phase or network cause can be inferred from this successful batch, and previous timeouts are not demonstrated fixed.

This is one successful two-turn synthetic trial, **not the required three-variant E01 acceptance PASS**, general model accuracy, or evidence for E02/E03. Raw reports retain automatic verdicts separately from this manual review. The harness completed its shutdown/process-group cleanup and temporary-directory removal before writing finishedAt. No further AI calls are authorized by this report.
