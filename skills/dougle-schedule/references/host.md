# Host scheduling and session ownership

Discover capabilities at runtime; names below describe official Claude facilities, not tools guaranteed to exist. Host configuration, version and company provider can disable them. Use tool schemas supplied by the actual host. A slash command printed in text is not a scheduled job.

## Claude Code

When exposed, CronCreate/CronList/CronDelete provide session scheduling; `/loop 2m <prompt>` is a user-facing alternative when the host supports it. Register the actual `dougle-schedule check <id>` invocation through the available facility, explicitly marking it draft-only, one bounded tick, saved state, no blocking questions and no posting. A recurring callback must never invoke start or create another timer. Use `*/2 * * * *` for a requested two-minute cron schedule; verify job creation/listing before reporting active. Do not call shell crontab, create launch agents or fabricate scheduler tools.

Some requested intervals cannot be represented evenly by minute-field cron (e.g. */7 has an uneven hour boundary). Do not silently round: use an actual duration scheduler if available or ask the user to choose an accurately supported cadence. Store requested and effective cadence separately. Reject zero/negative/fractional/ambiguous intervals with a short clarification. Default2 means no interval question is required on ordinary start.

Scheduling depends on the open host session/machine and can be delayed while the agent works; schedules may expire or be restored differently by host version. Inspect actual status and expiry rather than hardcoding a persistence promise. A new session must verify/re-register the job, not trust a saved ID. Never enable cloud execution or transfer Slack/company context to a cloud task as a fallback. If only /loop guidance is possible, report waiting for the user to execute it, not scheduled.

## Codex and other hosts

Use an equivalent local scheduling capability only if it actually exists and can access this session's tools/state. Do not invent a Codex /loop or assume GUI automations support two-minute scheduling. Without such support, this skill can perform explicit `check`, draft review and approve actions but MUST report recurring monitoring unavailable. Do not maintain a blocking sleep loop or spawn an external AI every two minutes.

## Dedicated session

A user-opened dedicated session is the portable first version. Opening terminal tabs automatically is optional and host-dependent. Only use an explicitly available, documented terminal/session tool for a user-requested new session; verify handoff and ownership before starting. Do not install terminal helpers or run startup shell commands copied from Slack. Only one verified owner per workspace/channel; if state storage provides no atomic ownership primitive and another session might be active, stop for explicit takeover after pausing the old owner. Do not claim Markdown implements distributed locking.

## Sources checked during development

- https://code.claude.com/docs/en/scheduled-tasks — host scheduling, tool discovery, lifecycle and limitations.
- https://docs.slack.dev/reference/methods/conversations.history/ — bounded history and pagination.
- https://docs.slack.dev/messaging/retrieving-messages/ — thread/message retrieval.

Product users need only the host's existing capabilities; these references are not authorization to install tools or widen access.

## Tick serialization

Before enabling recurrence, verify the host serializes tool-using turns for this owner. Store an in-flight run ID/start marker, cleared only after checkpoint persistence. This marker is diagnostic, not an atomic lock. Concurrent manual/timer callbacks require a real host atomic claim or verified serialized queue. If neither exists, do not enable recurring processing; offer manual checks. A stale in-flight marker after interruption requires inspecting durable partial work before recovery; do not assume expiry means safe concurrent takeover. Only the verified owner may clear it after confirming the old run stopped.

## Polling load

The built-in default for NEW monitors is2minutes. Existing stored cadences, including an earlier5minute default, remain unchanged unless explicitly requested. A quiet monitor still wakes the host AI and calls Slack readers; skipping dougle-ask is not zero token or network cost. A2minute nominal cadence gives30checks/hour versus12at5minutes; a check can require multiple history/thread pages. Keep reads incremental and bounded, avoid re-drafting unchanged items, retain the existing serialization/backoff rules, and return quietly on no changes. Observe actual tool rate limits and Retry-After, preserving incomplete windows and next-allowed retry time instead of spinning/reissuing calls. Do not auto-change the user's cadence or claim measured resource savings. Host scheduling can add jitter or delay, so this is not an exact120second SLA.
