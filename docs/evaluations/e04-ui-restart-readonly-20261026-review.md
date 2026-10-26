# E04 first readonly UI trial — handoff failure, not app failure

Artifact `e0e43e1084177ea4d28530547cc762bce339d2de2f5721d21ce70d0ef4eeb58d` remained unchanged. [Original raw report](e04-ui-restart-readonly-20261026.json) is preserved without edits; log `/tmp/dougle-e04-ui-restart-readonly.log`.

Actual dispatches: **2 = 1 STOP plan + 1 successful answer**. Variant0 answered that the synthetic BLUE badge is valid72hours from issuance, grounded in R1. The backend restarted on the same temporary database and complete before/after snapshots matched.

Parent Ego reports visually verifying the actual history row, saved72-hour answer and expanded source after restart. [Parent screenshot](../screenshots/e04-readonly-restart-variant0.png) is supplementary UI evidence, not a completed three-variant test or independently verified screenshot by this agent.

The parent created `ack.json` through apply_patch, initially0644; the harness polled before chmod and correctly rejected it with `Private regular acknowledgment required`. Cleanup then removed the directory, so the attempted chmod returned ENOENT. This is a checkpoint handoff race, **not an app/model defect**. The report correctly retains `uiReopenObserved=false`: no valid private acknowledgment completed. No followup, automatic retry or additional variant executed.

Owned backend PIDs82466 and82563 both exited; the owned temporary database/control directory was removed. Cleanup completed `2026-10-26T10:38:25.640Z`, JAR hash rechecked unchanged and lease released. E04 is not promoted.

Future acknowledgment workflow: apply_patch a private-directory `ack.pending.json`, chmod0600, then atomically rename to `ack.json`. Do not patch the watched acknowledgment path or relax ownership/permission checks. Any separately authorized new batch has its own report and fresh state; it cannot erase or retroactively complete this trial.
