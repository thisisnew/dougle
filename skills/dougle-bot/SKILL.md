---
name: dougle-bot
description: 'Set up and manage the optional local Dougle Slack bot: start, status, stop or add a channel. Uses a separate local runtime and Keychain credentials; never posts through the host AI; the runtime answers verified inquiries and privately requests developer assistance when needed.'
---

# Dougle bot

Use respectful Korean 존댓말 for all guidance and failures. Keep instructions short and actionable. This skill manages the optional runtime; it does not implement timers, answer Slack inquiries itself, or replace dougle-schedule.

Run the bundled `scripts/bot.sh status` using its absolute path first. Resolve paths relative to this installed skill, never the current project. A missing runtime/config is first-run setup, not a connection failure. Read [setup](references/setup.md) when setup, runtime upgrade, missing credentials or channel registration requires it. If runtime_installed=false but configured=true, prepare only the new runtime; preserve configuration and Keychain entries. After preparing, check status: update_required=true means an old or unverified process is still running. Explain that the new behavior requires stop confirmation then start; never report a plugin reload alone as a running bot upgrade. Never read token values, user memory, the bot DB or provider credential files with host tools. Use runtime status/doctor output instead.

## Entry menu

When invoked without an explicit action and configured, present exactly:

```text
✦ dougle bot
1. 시작
2. 상태 확인
3. 중지
4. 채널 추가
```

Wait for a choice; showing the menu does not start the bot. Natural language actions also work.

- **1 시작:** run `scripts/bot.sh start`. Report success only if runtime reports healthy=true, state=running and update_required=false. If still starting, poll status briefly; surface setup/connection failure honestly. Explain that starting enables automatic replies in the configured channels by default; explicit require_approval=true keeps review mode. The user explicitly authorizes receiving future mentions by choosing start.
- **2 상태 확인:** run `scripts/bot.sh status`; show process/connection state, running_version/update_required, registered channels and active inquiry IDs, actual phases and elapsed time. Explain that elapsed time is since receipt, not a completion estimate. A saved configuration or live process alone is not a connected Slack listener. Status does not start anything.

  For a latency question, summarize the relevant `recent_timings` record: queue/processing milliseconds, observed stage times and retry reasons. These are event-to-event wall times, not exact tool CPU time, model reasoning time or billed tokens. Character counts are not tokens. Never read the DB directly to supplement missing metrics; older inquiries may have none.
- **3 중지:** run `scripts/bot.sh stop`. Report stopped only when confirmed; if stopping, show that ongoing work is closing. Preserve credentials/configuration/history/drafts. New mentions received while stopped are not imported on restart. Previously accepted unfinished inquiries remain recoverable. A request already sent to Slack may be unresolved; never promise to undo it.
- **4 채널 추가:** ask for the Slack channel URL or ID. Existing same-app Keychain credentials are reused. If credentials are missing, guide the `security add-generic-password ... -w` commands in setup; never collect token values in chat. If running, explain that channel changes require a short stop and let the user choose whether to stop now. Then run `scripts/bot.sh channel-add CHANNEL`. Guide `/invite @dougle` in that channel if needed, and use `doctor --online` to verify read access. Show the menu; do not silently restart. Adding a channel never broadens to every channel.

## Boundaries and errors

Tokens are entered by the user directly into a real terminal's Keychain prompt. No token argument, environment variable, file or chat paste. If an error occurs, show the safe error code and the next relevant action; do not dump raw logs or DB contents. Refer to setup for prerequisites. Do not uninstall or replace users' Claude installations or shell aliases.

The bot invokes installed dougle-ask, i-have-adhd and hidden plain-language on each engine call, including resume; successful tool results are checked. The runtime automatically posts verified replies by default. Only inquiries requiring operational/developer help or internal failures create a DM for the configured approver_user_id (now the internal contact). Never use host Slack MCP to post on the runtime's behalf. Explicit require_approval=true retains exact latest-card review; do not change that setting silently. Failed style checks do not produce an approvable answer. A token/PII scan and known Korean ending checks are not guarantees of factual accuracy or complete sensitive-data detection.

The first preview is serial and local. It requires a running Mac, authenticated Claude CLI with Dougle installed, and authorized Slack app access. No auto-start on login, stopped-period catch-up, secret migration, automatic SQL execution, or OS-wide sandbox is implied. Engine hook/tool policy is an additional runtime boundary; use read-only company credentials. If the company restricts app installation, stop and explain the needed company approval.

## Slack presentation

Public replies contain a concise, respectful explanation for non-developers by default. Keep business conditions, exceptions and uncertainty; technical implementation details only on explicit request. Sources are retained locally and in internal issue DMs, not the public answer. No public Dougle footer: the bot account supplies identity. Ordinary dougle-ask citations stay unchanged. Progress appears on the triggering question as two reactions: persistent :dougle: identity plus ⏳ during queued/drafting/approval/lookup/posting. Only the state changes to ✅ after posting, ⚠️ on failure/uncertain delivery, or ⛔ on rejection/cancellation. If Slack rejects the missing dougle name, show only the state emoji. Normal automatic replies create no DM. An issue DM shows the question and the specific developer request; submitted results continue the same inquiry and update that card. While queued or drafting it shows only progress and a Cancel button. Every ~30 seconds the runtime updates actual CLI activity stage, elapsed time and time since the last signal; silence is not proof of a hang. No guessed percentage or completion estimate. These are last-known stages, not a live heartbeat or percentage. Issue DM and subsequent updates use the same card. Same thread resumes its session; a new channel-level mention is a separate session. Cross-thread conversation linking is not implemented.


The designated approver may cancel one queued/drafting inquiry in its latest DM; this stops its owned AI process and discards late output without stopping other inquiries. Already completed reads or external requests cannot be undone. Cancelled inquiries stay cancelled on restart; whole-bot Stop remains a different operation.

Operational/code/UI checks use Submit results / Reject. Runtime failures use only Submit processing result. Both request a minimal non-sensitive summary and observed time, not a retry/regenerate decision. Use the specific card; freeform DM messages are not ingested. Developer results are evidence only for the requested kinds; an environment repair does not replace actual reads. Wait if the result is insufficient. Never promise an ETA or claim delivery/verification without evidence. Delivery uncertainty must not be automatically retried.

In explicit review mode, controls remain Post / Edit / Reject; Reject has no reason form. Existing review drafts are never auto-published by an upgrade. Starting the new runtime with no require_approval setting enables automatic mode for new work; explain this during upgrade. Status reply_mode shows the configured mode. Avoid routine closing offers and boilerplate date/production caveats; retain limitations material to the question.

For Slack answers, the latest question sets the scope: where/who/when gets a direct answer; overview gets the main path; explicit detailed requests expand within the requested topic. Follow-ups add only new requested information. Preserve relevant conditions and unknowns; never truncate to a character count.

Uploaded Slack PNG/JPEG images are passed as actual image inputs, including prior images in the same thread (max4, 5MiB each, 12MiB total). Existing apps need files:read and workspace reinstallation for this feature; read setup for guidance. Missing/unsupported/failed images stop the draft with an actionable DM. Do not claim a private URL or filename is vision input. Other image formats, external image previews and freeform DM image lookup results are unsupported. The bot does not store image bodies; provider session retention is separate.
