# .25 seeded implicit-follow-up diagnostic b2

One separately authorized diagnostic, maximum **two dispatches**, completed with exactly **one real plan and one real answer**. Run: 2026-10-26 14:56:38–14:59:56 UTC, terminal session 3516, exit 0. No retry, remainder or additional batch. Report: `context-protocol-025-seeded-b2-20261026.json`; log: `/tmp/dougle-context-protocol-025-seeded-b2.log`.

## What was reproduced

The preserved b1 report was SHA-256 pinned before reading its two successful, manually reviewed questions/answers. The runner created new local bundles from the same synthetic policy and inserted those exact answers as terminal synthetic history, stopping the owned backend before each DB insertion. Seeding performed zero AI dispatches. Before the actual question, restored context was checked for exact question/answer order and zero omissions/truncation.

Actual question: “그럼 그 배지도 자동 갱신돼? 누가 승인해야 해?”

Actual plan: STOP. Actual answer: “AMBER 배지는 자동 갱신되지 않으며, 갱신에는 방문 안내 담당자의 수동 승인이 필요합니다. [R1]”

AGENT manually inspected the exact question, both prior exchanges, explicit AMBER correction and R1. The answer correctly resolved the pronoun, named the visitor-desk approval role and denied automatic renewal, without inventing records or current operational status. A checkpoint/result-hash-bound PASS ACK was written using apply_patch and atomically published with mode 0600. Automatic regex matching was not used as semantic approval.

**This is seeded history, not an uninterrupted third-turn reproduction.** Workspace/source/bundle/job IDs and digests were new, the backend had restarted during seeding, and the protocol wrapper changes timing. Exact prior conversational text does not mean an identical complete serialized prompt or process state. The seeded answers are not counted as newly generated answers.

## Safe counter observations

Existing byte-preserving protocol wrapper; one app-server counter file. No new directional counters, product changes, raw protocol payload, credentials, reasoning or raw thread/turn IDs were logged by the wrapper.

| Counter | Observed |
| --- | --- |
| initialize/config/thread/turn RPC responses | 1 each |
| unexpected/invalid responses | 0 / 0 |
| matched agent start/delta/complete | 1 / 49 / 1 |
| matched turn completion, status COMPLETED | 1 |
| ignored agent lifecycle or turn-completion events | 0 |
| stdout bytes / lines | 39,794 / 68 |
| invalid JSON/envelopes, oversized lines, capture limit | all 0 |
| pending line bytes / early pending | 0 / 0 |

Ignored application-binding categories were metadata: TURN_STARTED missing direct turn 1 (normalized nested-turn matching 1), THREAD_STARTED missing direct thread 1, THREAD_STATUS missing turn 2, OTHER missing thread 2/missing turn 2. One pre-stream OTHER notification was counted separately. Unknown OTHER methods were not retained, so their meanings cannot be inferred. No foreign-thread/foreign-turn answer lifecycle was observed.

This run shows the initialized/configured/accepted-turn path progressing to a bound answer and completion. It **does not reproduce or diagnose** b1's WAIT_RESPONSE failure, classify b1's six ignored bindings, or distinguish b1 provider latency from an application/transport issue. A successful seeded/instrumented run is not evidence that the timeout is fixed. No binding-acceptance relaxation or speculative root-cause fix was made. The wrapper adds forwarding/persistence overhead; counters are per process, not a general ordering mechanism for random filenames.

## Pins and cleanup

- Frozen .25 JAR SHA-256 `5cf78b6441a99f893ecbaf6e81babd3e17e01e46b154f3c772e43abe138ca22a`; finalized Intel bundle, not installed app; owned read-only snapshot used.
- Codex SHA-256 `73697f229103f463acaafd8659976caaa4892ec11e2c246c7fa97f93d523f90e`; Java SHA-256 `e5df547d287f27e7e8d4d78e8133c0ba9833c512f60de67bffa46c609a93ac8f`.
- Counter helper SHA-256 `831f6a62ab9cd204ae56540021bb26665fdca557e21cb346ccbf9f1d7399ab4e`; hook SHA-256 `81dbab35cadef88a3a5d406c283b9e7ccb447cdc2dc95992daf47f1427beca1c`.
- New report SHA-256 `e91f4c52d758a0f4409d699e55c46123df038c31f6cae4c1c3388cfd15b2caf8`.
- Original failed b1 report unchanged: `1db06e9d5c98ecfad55d7a81fbf38f86b27d648f689bf84b4224a8e590b07b50`.
- Executed harness SHA-256 `3ba980366861872b95c231bada18ff32d700821fb61c2bbf1e1f9faf6e13ef7d`; fixture SHA-256 `73f98ab5b42650e1ed55ebcf007df2ec583f7f2a285b91e616dcf8c3804d9a60`.

PIDs **40218, 40404, 40537** were independently verified absent. Owned `dougle-expert-live-kqLHlD` directory, DB, snapshot, wrapper/manifest and temporary numeric counter files were removed after counters were collected into the private report. JAR source/snapshot, Java and Codex hashes remained unchanged. Dedicated profile was used via CLI without credential reading/copying by the evaluator. No user DB, operational provider, target JAR write, build or installed-app change. **Immutable Intel-bundle lease explicitly released.**

Thirty-one offline fixture/wrapper tests passed before execution. Actual provider model ID and billing were not independently measured. This is one accurate seeded follow-up and a successful instrumented transport observation, not E04/E05 or central-50 acceptance promotion.
