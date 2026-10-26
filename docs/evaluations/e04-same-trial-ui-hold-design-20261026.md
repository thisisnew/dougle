# E04 same-trial UI hold — offline implementation, not executed

Batch13's backend restart/GET checks passed, but its temporary database was cleaned up. A reconstructed database cannot retroactively prove UI reopening of those trials. E04 remains partial and unpromoted. Do not repeat completed batches13/14 because of the later network outage.

## Read-only policy and authorization prerequisite

External business systems must be read-only even after approval: no Slack posting, DB writes or browser mutations. Local workspace/chat/index persistence remains necessary. Do not install/publish the 0.1.21 snapshot containing outbound Slack send. A future E04 run needs a separately authorized, policy-compliant artifact after enforcement changes; no automatic reuse of an outbound-capable artifact. This design authorizes no AI, external write or runtime launch.

## Proposed finite trial

Three fresh synthetic variants, two turns each, maximum **12 external AI dispatches total** (six plans and six answers). Start each variant only after the preceding variant's manual review succeeds. Stop on first failure, uncertainty, semantic mismatch or operational proposal; no retry. Configure no external business connector.

1. Start an approved pinned JAR with an owned temporary database and OS-assigned localhost port. Create synthetic policy source and thread; generate only the first plan/answer with fresh approval.
2. Snapshot thread ID, ordered bundles/digests, full answers and plan/answer job IDs. Stop the owned backend but retain the **same** database/files. Restart with fresh local session/port and compare snapshots.
3. Enter `WAIT_UI_REOPEN` before any followup POST. Keep the trial runtime/database alive under its explicit lease. Send parent only nonsecret URL, variant/thread/checkpoint IDs, allowed action and expiry; no credentials/session values in logs or reports.
4. Parent uses Ego on that exact isolated origin: select the synthetic Workspace, open its existing history row, verify first question/answer/citations/order, and save a synthetic-only screenshot and method/path trace. Opening must create no plan/answer or operational job.
5. Parent acknowledges the exact checkpoint with observed IDs and screenshot/trace paths. Harness compares unchanged job inventories and bindings. Reject stale, duplicate or wrong-variant acknowledgments.
6. After exact acknowledgment and unchanged-state validation, the **harness** submits the predetermined followup using the existing dispatch budget and fresh approval/latest parent/same thread. The browser cannot submit. This proves a UI reopen followed by an API-driven continuation, not UI-submitted followup or native desktop bootstrap; retain that limitation when deciding E04 acceptance.
7. Review both full answers and final history/evidence; verify one thread row. Close the isolated Ego tab, stop all owned processes and delete the trial directory before advancing to the next variant.

## Implemented hold/interlock

- States: `FIRST_TURN_COMPLETE`, `RESTART_VERIFIED`, `WAIT_UI_REOPEN`, `UI_REOPEN_VERIFIED`, `FOLLOWUP_OBSERVED`, `REVIEWED`, `CLEANED`, plus terminal failure/expiry. Bind acknowledgment to artifact SHA, checkpoint nonce, variant, thread ID and original bundle digest.
- Proposed fixed hold expiry: **15 minutes**. On expiry/abort, forbid further dispatch, stop only owned processes and clean tempdata. Preserve reports/screenshots, not a live DB. No automatic resend after network recovery or automatic expiry extension.
- Keep desktop authentication enabled. Instead of setting `window.__FORGE_DESKTOP_SESSION__`, an ephemeral loopback read-only bridge injects the generated session server-side. Ego receives no token, query secret or persisted credential. This does not test native desktop bootstrap. Host/origin checks, a scoped route allowlist, bounded responses and a fixed checkpoint deadline constrain the bridge.
- The browser cannot consume AI permits: all mutation requests are blocked, never forwarded, and fail the checkpoint. The sole nonfatal exception is App's automatic provider-connection POST: it receives an explicit 409 `CONNECTION_CHECK_NOT_PERFORMED`, without contacting the backend/provider. The UI therefore shows connection-check failure during this restricted inspection; no authenticated-state result is fabricated. Initial scoped `entries?area=ORIGINAL` GET is allowed; library navigation is not part of this checkpoint.
- Harness POSTs are forbidden while waiting. Followup consumes the remaining two existing permits only after exact acknowledgment. No browser-submission budget or two-actor generation interlock is claimed.
- Record method/path/status, IDs, immutable snapshots and synthetic screenshots only, never auth headers/cookies. Keep the JAR/runtime lease throughout the hold; release explicitly only after cleanup, before documentation review.

## Parent control protocol (future authorization required)

`scripts/expert-live-eval.mjs --hold-at-restart` is opt-in and requires restart scenarios plus `FORGE_EXPERT_READONLY_ARTIFACT_SHA256` equal to the pinned `FORGE_EXPERT_JAR_SHA256`. It rejects old675a7c59 explicitly. A hash pin is not proof of policy compliance; parent must authorize the new read-only artifact separately. Current native metadata0.1.22/DBv28 is not itself a new tested JAR.

At `WAIT_UI_REOPEN`, the harness reports only a private control-file path, loopback UI URL, workspace/thread IDs and expiry. A unique owned0700 directory contains0600 `checkpoint.json`; neither file nor report contains the session. Parent Node reads this file privately, opens `uiUrl` in Ego (no bootstrap script needed), and inspects the exact existing history row, prior answer and citations. Do not submit, switch workspaces or trigger settings actions. The newly created synthetic workspace is already active before restart.

After inspection and a synthetic screenshot, write a new0600 temporary acknowledgment in the same directory and atomically rename it to the supplied `acknowledgmentPath`. Copy only `checkpointId`, `artifactSha256`, `workspaceId`, `threadId`, `variant`, `bundleIds`, `bundleDigests`; add `observed: "HISTORY_REOPENED_NO_SUBMIT"` and `screenshotPath: "docs/screenshots/<synthetic-name>.png"`. No other fields are accepted. Never reuse acknowledgments across variants or runs. A screenshot path is reviewer evidence, not independent visual verification by the harness.

If using `apply_patch`, create **`ack.pending.json`**, never `ack.json`: patch-created files may initially be0644 and the poller can reject them before a later chmod. Fill the exact current acknowledgment in the private checkpoint directory, then publish only after permissions are correct:

```sh
chmod 0600 "$CHECKPOINT_DIR/ack.pending.json" &&
mv "$CHECKPOINT_DIR/ack.pending.json" "$CHECKPOINT_DIR/ack.json"
```

Both paths must be in the same owned checkpoint directory for atomic rename. Use the directory and acknowledgment path from the current control file, ensure no acknowledgment already exists, and finish before expiry. Never chmod an already published acknowledgment, recreate an expired directory or weaken the private-file check. The first readonly E04 trial demonstrated this race and stopped safely; its evidence remains separate from any newly authorized batch.

The harness requires successful GETs of both the scoped history list and exact thread, validates acknowledgment binding/expiry and unchanged persisted snapshots, then resumes. Stale/mismatched/unsafe/symlink acknowledgments, mutation attempts, transport failures, state drift, abort or expiry stop the hold without followup. Bridge sockets and private controls are cleaned on exit; the enclosing harness owns backend/tempdata cleanup. Close the Ego tab after inspection. The deadline is at most15minutes, including parent notification and snapshot validation; no extension/retry.

Offline tests in `scripts/expert-live-ui-hold.test.mjs` cover private files, scoped forwarding, no-token reports, denied submission, the mount-only connection rejection, stale acknowledgments, expiry, state drift, abort/error/symlink cleanup and hung callbacks. These use fake loopback servers only. No actual UI or JAR hold has run. Parent must review the bridge and grant a fresh run after external-read-only enforcement is validated.

Completion requires three actual same-trial UI reopens after backend restart followed by grounded same-thread answers, with the API-driven-followup limitation explicitly reviewed. UI screenshots and API behavior from different reconstructed trials cannot be combined as that evidence. E04 remains unpromoted; this offline work changes no inventory status.
