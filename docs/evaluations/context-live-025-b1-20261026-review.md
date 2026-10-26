# .25 real Codex short-context batch b1 — partial, timeout stop

One authorized synthetic batch, 2026-10-26 14:43:17–14:48:15 UTC, terminal session 67110, exit 1. Purpose: initial target → explicit correction → implicit follow-up → backend restart and correction recall. Budget was eight dispatches for this batch only, not a global authorization cap. The run stopped at its first failure after **six dispatch attempts: three plans, three answers**. No automatic retry, remainder execution, or further batch occurred.

## Manual semantic results

| Turn | Question purpose | Actual outcome | Manual conclusion |
| --- | --- | --- | --- |
| 0 | BLUE policy, validity and approval role | BLUE, 72 hours from issuance, no auto-renewal, operator manual approval; R1 cited; explicitly synthetic | PASS for this answer |
| 1 | Correct target from BLUE to AMBER; ask validity | AMBER, 24 hours from issuance; R1 cited | PASS for this answer |
| 2 | Implicit “that badge” renewal and approval | Answer job timed out; no completed answer | Not semantically reviewable |
| 3 | Recall corrected target after backend restart | Not dispatched; restart not reached | NOT_RUN |

Each completed answer was manually compared with the exact question, original R1 policy and prior context before an AGENT checkpoint/hash-bound ACK was written using apply_patch, chmod 0600 and atomic rename. Parent independently reported reviewing turns 0/1 with the same conclusions. This is agent/parent review, not an independent human-user panel or automated regex approval. Automatic checks were only an additional stop gate.

All three actual plans succeeded with STOP; no operational proposals or tool executions occurred. The turn-2 saved bundle contains the original BLUE exchange and explicit AMBER correction/24-hour answer, with the correct parent bundle. Extractive context reports two prior questions, zero omitted questions/answers and no truncated answer IDs. This demonstrates that the captured local context retained the correction; it does not prove what caused the absence of a completed model response.

## Failure diagnostics — no root-cause attribution

The third answer failed at the application's Codex app-server answer-generation deadline (approximately 120 seconds), phase `WAIT_RESPONSE`:

| Observation | Value |
| --- | --- |
| stream phase / turn accepted | TURN_ACCEPTED / true |
| messages / deltas / completed messages | 0 / 0 / 0 |
| matched completions / ignored bindings | 0 / 6 |
| other notifications / protocol invalid | 0 / 0 |
| pending line bytes / callback active | 0 / false |
| stdout / stderr bytes | 24,544 / 0 |
| stderr signal / exec stdin written | NONE / false |

These are the frozen .25 artifact's existing safe counters. No protocol wrapper was enabled. The six ignored bindings are **not classified here by missing/foreign thread/turn or answer-lifecycle versus metadata**. No raw protocol events, IDs or payload were captured by instrumentation. Thus neither provider outage, incorrect binding acceptance, missing answer events nor harmless metadata can be established as the cause. The `answer` field in the failed evaluation result holds the application error string, not model-generated answer content.

This repeats the general historical WAIT_RESPONSE symptom, but is not proof that past failures had the same cause. Parent's new diagnostic source changes were not built into or substituted for this artifact. A useful next step is the separately coordinated frozen diagnostic artifact with classified safe binding counters, followed by a newly bounded reproduction; do not silently repeat this batch or relax binding validation.

## Resources, safety and cleanup

- Frozen .25 JAR SHA-256: `5cf78b6441a99f893ecbaf6e81babd3e17e01e46b154f3c772e43abe138ca22a`. Read from finalized Intel bundle and copied into an owned read-only snapshot. No target JAR write or installed-app changes.
- Finalized bundle Java/Codex/search models were used, not installed app resources. Java SHA-256 `e5df547d287f27e7e8d4d78e8133c0ba9833c512f60de67bffa46c609a93ac8f`; Codex SHA-256 `73697f229103f463acaafd8659976caaa4892ec11e2c246c7fa97f93d523f90e`. JAR snapshot/source, Java and CLI hashes remained unchanged.
- Private temporary HOME/DB, clean child environment and synthetic local folder only. The existing dedicated Codex profile was used through the CLI; credentials were not read/copied by the evaluator. Real Codex network access was authorized; this was not the offline E37 network sandbox. A strict harness API allowlist excluded operational provider/browser/Slack/capture routes.
- Backend PID **37219** exited; no process using the owned temporary path or live-eval entrypoint remained in the cleanup check. Temporary `dougle-expert-live-IoGSky` directory and snapshot were removed. No restart or UI hold occurred. Runtime lease released.
- Nineteen focused offline fixture/history/schedule/semantic-review tests passed before launch. They are not real-AI acceptance results.

## Durable evidence and limits

- Report: `context-live-025-b1-20261026.json`, SHA-256 `1db06e9d5c98ecfad55d7a81fbf38f86b27d648f689bf84b4224a8e590b07b50`.
- Log: `/tmp/dougle-context-live-025-b1.log`.
- Executed harness SHA-256: `8addc6e788caa138a30470f6fb6a1e08b90f434341101146b954eefb589f8fe1`.
- Fixture SHA-256: `462786d48c9e551b038f72d6a3d1e27d6f290ddf8fe2e43b6d8e780b918dfce0`.

Two completed answers are accurate for this small synthetic policy. Implicit follow-up accuracy and restart recall remain unverified. No long-history compaction, 32-turn memory, native UI or whole E04/E05 claim is supported. Actual provider model ID and billed call count were not independently observed; dispatch attempts are not billing telemetry. **No central acceptance verdict was promoted.**
