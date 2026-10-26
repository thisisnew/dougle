# E04 backend restart and E12 glossary — actual bounded evaluation

Artifact SHA-256: `675a7c59e13edc8ee849b23977699e600331555d8f53c669ef8e7f10406e3489`. Synthetic-only policy/glossary files and isolated temporary databases; existing dedicated CLI authentication, no credentials read. Reviewer: Codex evaluation agent, manual comparison of every answer with captured source quotes, not independent human review. Requested CODEX/default; actual model ID, tokens and currency cost unobserved.

Authorized maximum and actual total: **24 dispatches, 12 plan + 12 answer**, all successful. No failed or uncertain jobs and no automatic retries. Both stages used the unchanged artifact; no Maven/build/JAR write. Owned processes and tempdata were removed, no Java runtime remained at post-run check, and runtime/JAR lease was released before review documentation. Packaging load was concurrent; no causal latency attribution is made.

## Offline harness work

- Added owned start/stop lifecycle with fresh token, OS-assigned localhost port and JAR hash verification for every restart. Restart retains only the trial's database/files; final shutdown removes the trial directory. Recorded runtime PID/port/timestamps contain no credentials.
- Restart checkpoint compares complete thread detail, history list, individual immutable evidence bundles, plan jobs, answer jobs and generation jobs before/after shutdown. Followup checks same thread, unchanged old bundles/jobs, latest-parent binding, one history row, and repeated GET snapshots with no new job creation.
- `automaticPass=false` now stops after saving evidence, just like non-successful answer status. Operational proposals remain rejected for local-only scenarios. Method/path traces now cover ordinary evaluation too; no header/auth dumps.
- Added lifecycle/history tests and two three-variant synthetic fixtures. **16 offline tests passed** before real execution. These tests are not model calls or acceptance evidence by themselves.
- Subsequent narrow harness hardening restricts answer same-ID reconciliation POST to SUCCEEDED jobs only; failed/cancelled jobs must not be resubmitted even for reconciliation. No failed job occurred in these batches, so this does not alter their observed results. Raw reports pin the harness version actually executed.

## Batch13 / E04 — backend/API portion passed, UI not observed

[Raw evidence](investigation-live-batch13-e04-restart-20261026.json): 12 dispatches, six STOP plans, six successful answers, **three actual backend restarts** in the same temporary database. Four runtime PIDs used distinct OS-assigned ports. Each trial begins a separate thread; each restart occurs after a completed first answer and before its implicit followup. All three restart checks finish FOLLOWUP_VERIFIED.

| Variant | Manual answer review |
|---|---|
| 0 | First answer states BLUE badge validity is 72 hours from issuance. After restart, answer denies automatic renewal and requires operator manual approval. Both cite policy R1 correctly. |
| 1 | First answer gives 72 hours and correctly identifies the policy as synthetic. Restored followup identifies the operator's manual approval, citing R1. |
| 2 | First answer correctly states expiry and renewal constraints. Restored followup repeats manual approval and explicitly says detailed application steps are absent rather than inventing them. All claims grounded in R1. |

Recorded before/after/final snapshots preserve order, bundle digests, full answers and request/job identity. Followup history contains the prior turn and bindings remain in the same thread. Repeated history/detail GETs did not create jobs. No restart while a job was in flight, no uncertain-acceptance fault injection, and no E42 claim.

**UI reopening was not performed** (`uiReopenObserved=false`). This proves actual backend restart plus API history/evidence restoration and real post-restart semantic followup, not native-window/history-row interaction. E04 is therefore **partial evidence only**, pending parent UI review; central status is not promoted here. Temporary data is cleaned, not retained as a live UI hold. The policy text and complete saved evidence suffice to prepare a separate synthetic UI fixture without reading user data, but that would be a separate observation.

Six turn latencies exclude restart time: p50 **10665 ms**, nearest-rank p95 **42249 ms**, sum **124599 ms**. Overall report duration includes startup and three restarts. No timeout occurred; longer latencies are not attributed to CPU/network without measurement.

## Batch14 / E12 — artifact-scoped PASS recommendation

[Raw evidence](investigation-live-batch14-e12-glossary-20261026.json): 12 dispatches, six STOP plans, six successful answers, three distinct wording pairs. Source `synthetic-glossary.md` explicitly defines the Workspace's synonyms and distinguishes the wrong-domain state.

| Variant | Actual-versus-expected manual review |
|---|---|
| 0 | Maps “입고 검수 끝” to RECEIVING_CHECKED, then rejects equivalence with 출하 확정/SHIPPING_CONFIRMED. Correctly separates inspected incoming items from shipping approval. |
| 1 | Maps “수신 검사 완료” to RECEIVING_CHECKED, then rejects using 발송 확정 as its synonym. Both states and differing meanings match R1. |
| 2 | Confirms 입하 검수완료 and 입고 검수 끝 are synonymous in this Workspace, with RECEIVING_CHECKED; followup explicitly rejects merging with SHIPPING_CONFIRMED. |

Every answer cites glossary R1 and matches its definitions; no operational query/proposal, invented state, or wrong-domain substitution. **3/3 pairs and 6/6 answers manually correct.** This supports E12's local glossary acceptance on this exact artifact only, not a general synonym benchmark or operational record verification. Parent can promote after reviewing this evidence; this report itself does not change the central inventory.

Turn p50 **9696.5 ms**, nearest-rank p95 **10768 ms**, sum **57884 ms**, six samples. No human intervention during either stage beyond separate authorization and the E04 manual review before launching E12. No model-cost/human-efficiency estimate.

## Evidence boundaries

All twelve plans stopped on sufficient evidence; no SEARCH/capture branch was needed. Success telemetry is status-derived COMPLETED, not live failure-stage/counter measurement. E05's missing summary prerequisite remains unresolved. Neither batch establishes UI restoration, shutdown during generation, all operating-system recovery behavior or any additional case acceptance. No further AI calls are authorized by this report.
