# Local schedule management and restoration

Use the user's `~/.dougle/schedules/` regardless of cwd. Persist definitions and operational history separately from the host's disposable timer. This does not preserve a terminal process, credentials or full AI conversation. Do not access unrelated user memories merely to list schedules. Read the state and host protocols alongside this file for mutations.

## Layout

```text
~/.dougle/schedules/
  index.md                         small navigation list, not authoritative
  <opaque-local-id>/
    monitor.md                     existing definition/checkpoints/ownership
    events/<unique-event-id>.md     one immutable lifecycle event per file
    inquiries/<id>.md              pending drafts and delivery evidence
```

Index rows: schedule ID, workspace/channel label and IDs, requested interval, recorded desired state, monitor-relative path. Enumerate ordinary direct schedule directories as well as the index to find saved schedules after a missing/stale index. Reject traversal, symlinks outside the store and malformed records. Unknown/missing records are unavailable, not permission to create replacement state or erase history. Repair index pointers after verified record writes; never advance scan checkpoints during discovery. Same channel names in different workspaces are distinct; require the ID when ambiguous. Existing 0.6.0 monitor/inquiry records without an index or events remain valid: discover them, preserve their settings/checkpoints, and record only the newly observed management operation. Do not invent earlier creation/pause events or present unknown history as complete.

## List and history

`list` / `목록` / `스케줄 보여 줘`: show channel/workspace, cadence, **recorded desired state**, **observed timer state** (running in verified owner / not found in this owner / unknown-other-session / access error), last successful scan, and pending inquiry count. Do not label a saved active flag as currently running. Query the host timer registry only when permitted and in scope; lack of Slack access must not prevent reading local definitions. Only list and report; no channel reads, timer creation, ownership transfer or sending. Inspection errors do not overwrite the record with stopped.

`history <id>` / `이력`: read that schedule's lifecycle events in time order with ID tie-breakers, report a compact recent subset and allow older history on request. Show creation, interval changes, timer registration/restoration, pause/stop, failures/recovery, and scan/queue/delivery outcome summaries. Keep history local; exclude channel-wide transcripts, secrets, raw DB results and hidden reasoning. Individual reply receipts remain in inquiry records. A stored log entry is an observation, not authorization or proof that a timer is still alive.

## Persisting changes and history

Use one verified owner and serialized writes. Before a lifecycle side effect, write a unique immutable **intent** event with UTC time, local operation ID, action, actor/session, schedule ID, previous/requested settings and known timer binding. Confirm the write before changing any timer. Record success/failure/unknown as a new result event linked to the same operation ID; never edit an earlier event to claim success. A result records the observed returned ID/status, not the desired outcome. Update monitor.md after the observed result, then refresh the navigation index. Use actual host safe/atomic file replacement when available and preserve a backup before replacing monitor.md; do not assert transactional durability from ordinary Markdown writes.

If writing the intent fails, do not perform the timer side effect. If the timer succeeds but result/state persistence fails, report recovery-needed; leave the intent and reconcile the registry on next invocation before retrying. Do not issue a second create just because monitor.md lacks the new ID. For each create/replace operation put its unique local operation ID (the timer generation) in a supported metadata field OR in the callback text alongside the trusted schedule ID. Use that exact generation when recording the effective timer binding. Checks compare the callback generation with the confirmed saved binding and exit if it is missing, pending or superseded; a created-but-not-recorded timer cannot begin polling. Inspect existing jobs by the exact trusted schedule ID, callback, channel/owner and operation binding; timer ID alone is insufficient. If no supported field/callback/listing can expose this binding, ambiguous creation recovery is blocked for manual verification, never a reason to create another job. A missing result file is uncertain, not a failed operation.

For pause/stop, write local paused/stopped status and revoke approvals BEFORE timer cancellation so any remaining callbacks exit. If history intent/state cannot be written, do not claim the operation succeeded: explicit stop may still cancel a known owned job as a best-effort emergency stop, verify it and warn that persistent state/history was not saved. Never cancel jobs based only on a matching channel name or stale unrelated timer ID. Failure to cancel does not roll local stopped status back to active.

Each successful complete scan may add one compact outcome event (window end, new/pending counts), not a copy of every message; failed/partial scans log actual progress/error without advancing success times. Avoid unbounded duplicate error events for unchanged failures; record first occurrence plus recovery, preserving attempt details in monitor.md. Do not silently prune history. A user-requested cleanup must preserve active checkpoints, pending drafts and unknown-delivery receipts; do not delete posted Slack messages.

## Restore / resume in a new session

1. Read the exact saved definition and unfinished intents, scan/thread cursors, queue and send-intent/unknown-delivery records. Restoring means using this same schedule ID and settings, not creating a new schedule with today's start boundary. Missing/corrupt required state blocks restore; preserve it and explain the missing part. Do not infer defaults over saved custom cadence.
2. Verify dedicated-session status, actual scheduler capability, Slack workspace/channel and posting identity. Compare the trusted callback and owner against the actual timer registry. If the same owned timer survived, reuse it without duplication. A job not visible in this session may still run in another session: require confirmed old-owner stop and explicit takeover before assigning a new owner. Do not cancel unrelated tasks. Resolve EVERY unfinished lifecycle intent first (create, replace/interval, pause, stop and resume), not only creation. An unfinished local pause/stop intent takes precedence over an older active snapshot: hold polling, persist that intended paused/stopped state if possible, and reconcile cancellation of the verified owned timer before any restore. Do not erase or mark an unresolved operation successful. If state cannot be established, remain recovery-needed and do not reuse/create a timer. A later explicit resume can supersede that intent only after it has been resolved and the new action is recorded.
3. `restore all` is a preview of saved candidates, not permission to revive every stopped monitor. A general resume request applies to interrupted intended-active schedules only after ownership checks. Explicitly paused/stopped schedules remain so unless the user names them for restart or approves a concrete list that identifies those states. Show unsupported/blocked candidates rather than treating a partially successful batch as fully restored.
4. On an explicit authorized resume, re-register only a missing verified-owned timer using the stored interval/callback, recording intent/result. Keep the original ingestion boundary and incomplete fixed window; catch up from the last successful coverage with bounded paging. Do not promise historical completeness if Slack retention/access prevents reading the outage window. Persist and report the gap; do not mark it successfully checked or jump to now without the user's explicit acceptance of skipping the missing range.
5. Preserve all inquiry states. Old-session approvals are invalid; show drafts again and require a new exact approval. Unknown delivery stays blocked pending remote reconciliation and is never converted to pending just to resume. Saving a definition is not authorization to post. Recurring checks never send.
6. Verify timer registration plus persisted result before reporting restored. If only definition/queue was recovered, say so separately from recurring monitoring. The next callback loads this skill and the saved record; it must not rely on the previous session's context surviving.

## Commands users can say

- `/dougle:dougle-schedule 목록`
- `/dougle:dougle-schedule <id> 이력`
- `/dougle:dougle-schedule <id> 일시정지`
- `/dougle:dougle-schedule <id> 종료`
- `/dougle:dougle-schedule <id> 재개`
- `/dougle:dougle-schedule <id> 10분으로 변경`

Use the actually installed host skill name (Codex `$dougle-schedule`, Claude personal `/dougle-schedule`). A terminal window close is not a verified stop operation. Prefer explicit stop before planned terminal updates; retain the record for later explicit resume. No timer is recreated simply because a skill was installed, reloaded or a list was viewed.
