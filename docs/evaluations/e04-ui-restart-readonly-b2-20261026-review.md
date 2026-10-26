# E04 readonly restart/UI trials — partial evidence, no promotion

Both trials used immutable JAR `e0e43e1084177ea4d28530547cc762bce339d2de2f5721d21ce70d0ef4eeb58d`. They were separately authorized finite batches, not automatic retries. Their original reports remain unmodified. **E04 is not promoted:** neither trial completed a successful post-restart followup, and three complete variants were not exercised.

## First trial: acknowledgment handoff failure

- [Raw report](e04-ui-restart-readonly-20261026.json), [detailed review](e04-ui-restart-readonly-20261026-review.md), [parent screenshot](../screenshots/e04-readonly-restart-variant0.png).
- **2 dispatches: 1 STOP plan + 1 successful answer.** The answer correctly stated72hours from BLUE badge issuance. Same-DB restart snapshots matched.
- Parent visually inspected history, answer and expanded source, but apply_patch initially published0644 `ack.json`. The poller rejected it before chmod with `Private regular acknowledgment required`; cleanup then made chmod fail with ENOENT. This was a handoff permission race, not an app/model defect. No valid checkpoint acknowledgment or followup completed; raw `uiReopenObserved=false` remains accurate.

## Batch2: UI checkpoint verified, followup answer timed out

- [Raw report](e04-ui-restart-readonly-b2-20261026.json), [parent screenshot](../screenshots/e04-readonly-restart-b2-variant0.png). Console: `/tmp/dougle-e04-ui-restart-readonly-b2.log`.
- **4 dispatches: 2 successful STOP plans + 2 answer attempts.** First answer succeeded and correctly stated72hours from issuance; followup answer failed. Only variant0 was attempted; variants1–2 did not run.
- Backend restart on the same owned temporary database preserved complete snapshots. Parent Ego reopened that exact persisted thread and supplied a valid private acknowledgment via pending-file/0600/atomic rename. The saved trace includes successful history-list and exact-thread GETs; state/job comparison stayed unchanged. Report records `UI_REOPEN_VERIFIED` and `uiReopenObserved=true` for **one checkpoint**.
- The checkpoint was a read-only browser inspection, followed by a harness-submitted API question. It does not prove UI-submitted followup or native desktop bootstrap. The bridge rejected the mount connection check locally with409 and Slack inbox-count GETs with403; these were not forwarded as operational actions. Parent confirmed TaskSpace13 was closed after UI inspection.

### Safe failure diagnostics and uncertainty

Followup answer failed at the app-server deadline: transport `CODEX_APP_SERVER`, phase `WAIT_RESPONSE`, stream phase `TURN_ACCEPTED`, `turnAccepted=true`. Counters: `messages=0`, `deltas=0`, `completedMessages=0`, `matchedCompletions=0`, `ignoredBindings=6`, `otherNotifications=0`, `protocolInvalid=0`, `pendingLineBytes=0`, `callbackActive=false`. Stdout21417bytes, stderr0bytes, stderr signalNONE.

These show **zero matched agent messages/completions**, not proof that the provider generated nothing. The six ignored bindings are not categorized by event type or missing/mismatched binding field. No provider/network root cause, parser defect or stream-delivery explanation can be established from these counters alone. There was no completed followup answer to judge semantically. Do not describe this as an observed wrong answer, callback blockage or proven provider failure. Actual model ID and token cost remain unobserved; requested model was provider default.

## Cleanup, accounting and remaining scope

Batch2 stopped at its first failed answer without resubmission. Owned backend PIDs82953 and82978 exited, bridge/control files and temporary database directory were removed, and cleanup completed `2026-10-26T10:44:37.115Z`. JAR hash was rechecked unchanged and lease released. First-trial cleanup is recorded separately in its review.

Across these two separately authorized trials: **6 dispatches total = 3 plans + 3 answer attempts; 2 successful answers and1 timeout**. Do not count screenshots from separate trials as three variants or combine their partial steps into a completed E04 case. The first trial's visual observation is not another valid harness checkpoint; batch2 has one valid checkpoint but no successful followup. No automatic retry, remaining variant, inventory promotion or further AI call is authorized.
