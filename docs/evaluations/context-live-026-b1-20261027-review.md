# .26 four-turn actual-AI context probe

One authorized variant completed **8 dispatch attempts: 4 plans and 4 answers**, 2026-10-26 21:28:52–21:33:03 UTC (October 27 KST). Session 61945 exited 0. No retries, seeded history, protocol wrapper, UI hold or additional batch. Plans all returned STOP; operational tools were not executed.

Report: `context-live-026-b1-20261027.json`, SHA-256 `dddf338e6997b7782d4caea4829c2a52a199b2a0a10c3481100c31789a735ed78`. Log: `/tmp/dougle-context-live-026-b1.log`.

## Manual semantic review

Each complete answer was manually compared with its exact question, R1 and saved prior exchanges before an AGENT checkpoint/result-hash-bound ACK was written with apply_patch, chmod 0600 and atomic rename. Parent independently reviewed all four. Regex checks were supplementary, not semantic approval.

| Turn | Actual answer and review | Verdict |
| --- | --- | --- |
| 0 | BLUE: 72 hours from issuance, no automatic renewal, operator manual approval; expressly synthetic with no actual records/status. All match R1. | PASS, scoped |
| 1 | “AMBER 배지의 유효 기간은 발급 시각부터 24시간입니다. [R1]” Correct explicit BLUE→AMBER correction. | PASS, scoped |
| 2 | “AMBER 배지는 자동 갱신되지 않으며, 갱신하려면 방문 안내 담당자의 수동 승인이 필요합니다. [R1]” Correct implicit “that badge” resolution and approval role. | PASS, scoped |
| 3 | After backend restart: “대화에서 마지막으로 정정한 대상인 AMBER 배지의 유효 기간은 발급 시각부터 24시간입니다. [R1]” Followed by no automatic renewal and visitor-desk manual approval. Correct latest target and policy. | PASS, scoped |

Turns 0–2 used the same uninterrupted backend; only the planned restart occurred before turn 3. Restart snapshots and idempotent answer replay checks passed. Final context retained all three prior exchanges with no omissions/truncation. Turn elapsed times (local preparation, plan and answer combined, excluding manual review) were 14,499 / 14,254 / 12,481 / 15,437 ms; these are not provider latency measurements.

## Timeout interpretation

The .25 b1 third-answer 120-second WAIT_RESPONSE failure **was not reproduced**. All eight outcomes were SUCCEEDED/COMPLETED. Successful job diagnostics do not expose the new failure-only detailed binding counters, so no counts for missing/foreign thread/turn or ignored answer lifecycle were observed in this batch. Absence of those fields is not a zero-count measurement.

This run neither classifies the old six ignored bindings nor proves a provider, transport or application root cause/fix. It is one four-turn variation, not full E04/E05, long-history compaction, native UI acceptance or reliability evidence across repeated trials. Central acceptance verdicts remain unchanged. Actual provider model ID and billing were not independently observed; dispatch counts are not billing telemetry.

## Pins, isolation and cleanup

- Installed .26 resources were read-only inputs; JAR copied to an owned mode-0400 snapshot. JAR SHA-256 `f88d6f01b115c5951a98c35dd6723b447c4bce1da03843666a8ad5f64f368093`; source/snapshot unchanged after execution.
- Bundled Java SHA-256 `e5df547d287f27e7e8d4d78e8133c0ba9833c512f60de67bffa46c609a93ac8f`; Codex SHA-256 `73697f229103f463acaafd8659976caaa4892ec11e2c246c7fa97f93d523f90e`; both unchanged. Bundled local models verified; no build/package/install changes.
- Private HOME/SQLite and synthetic folder only; existing dedicated profile used through CLI without evaluator credential reading/copying. No user DB or business data access. Loopback API allowlist excludes operational providers; real Codex network access was authorized, not an offline network-sandbox claim.
- Backend PIDs **57829 / 58872** independently verified absent. Owned directory `dougle-expert-live-RZ4c5m`, snapshot and DB removed. Process session exited successfully. **Installed-resource lease RELEASED.**
- Old .25 b1/b2 report hashes remain `1db06e9d5c98ecfad55d7a81fbf38f86b27d648f689bf84b4224a8e590b07b50` / `e91f4c52d758a0f4409d699e55c46123df038c31f6cae4c1c3388cfd15b2caf8`.

Harness changes only: immutable explicit .25/.26 artifact allowlist, matching approval pin, dynamic snapshot version label/report version. Unknown/prototype-key pins, mismatched approval, changed budget/variant and protocol instrumentation remain rejected. **25 focused offline tests passed** (`/tmp/dougle-context-026-guards.log`); these are not actual-AI results. Executed harness SHA-256 `65c634590407e89fb6d78f0ecffce686005c35f63b82e882923d3b8d9af42f3d`; fixture SHA-256 `f0a062b015ae7c022325015e2efe8a6561272fa4dcb827a8fb440496d5ee7808`.
