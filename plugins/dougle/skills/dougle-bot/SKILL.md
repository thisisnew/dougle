---
name: dougle-bot
description: 'Set up and manage the optional local Dougle Slack bot: start, status, stop or add a channel. Uses a separate local runtime and Keychain credentials; never posts a reply through the host AI or bypasses the designated Slack approver.'
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

- **1 시작:** run `scripts/bot.sh start`. Report success only if runtime reports healthy=true, state=running and update_required=false. If still starting, poll status briefly; surface setup/connection failure honestly. Starting does not approve any reply. The user explicitly authorizes receiving future mentions by choosing start.
- **2 상태 확인:** run `scripts/bot.sh status`; show process/connection state, running_version/update_required, registered channels and active inquiry IDs, actual phases and elapsed time. Explain that elapsed time is since receipt, not a completion estimate. A saved configuration or live process alone is not a connected Slack listener. Status does not start anything.
- **3 중지:** run `scripts/bot.sh stop`. Report stopped only when confirmed; if stopping, show that ongoing work is closing. Preserve credentials/configuration/history/drafts. New mentions received while stopped are not imported on restart. Previously accepted unfinished inquiries remain recoverable. A request already sent to Slack may be unresolved; never promise to undo it.
- **4 채널 추가:** ask for the Slack channel URL or ID. Existing same-app Keychain credentials are reused. If credentials are missing, guide the `security add-generic-password ... -w` commands in setup; never collect token values in chat. If running, explain that channel changes require a short stop and let the user choose whether to stop now. Then run `scripts/bot.sh channel-add CHANNEL`. Guide `/invite @dougle` in that channel if needed, and use `doctor --online` to verify read access. Show the menu; do not silently restart. Adding a channel never broadens to every channel.

## Boundaries and errors

Tokens are entered by the user directly into a real terminal's Keychain prompt. No token argument, environment variable, file or chat paste. If an error occurs, show the safe error code and the next relevant action; do not dump raw logs or DB contents. Refer to setup for prerequisites. Do not uninstall or replace users' Claude installations or shell aliases.

The bot invokes installed dougle-ask and i-have-adhd on each engine call, including resume; successful tool results are checked. The bot prepares a DM draft; only the configured approver's exact latest-card approval may post. Never use the host Slack MCP to simulate that approval or post on the runtime's behalf. Failed style checks do not produce an approvable answer. A token/PII scan and known Korean ending checks are not guarantees of factual accuracy or complete sensitive-data detection.

The first preview is serial and local. It requires a running Mac, authenticated Claude CLI with Dougle installed, and authorized Slack app access. No auto-start on login, stopped-period catch-up, secret migration, automatic SQL execution, or OS-wide sandbox is implied. Engine hook/tool policy is an additional runtime boundary; use read-only company credentials. If the company restricts app installation, stop and explain the needed company approval.

## Slack presentation

Public replies contain a concise, respectful explanation for non-developers by default. Keep business conditions, exceptions and uncertainty; technical implementation details only on explicit request. Sources are retained in the approver DM, not the public answer. No public Dougle footer: the bot account supplies identity. Ordinary dougle-ask citations stay unchanged. Progress appears on the triggering question as two reactions: persistent :dougle: identity plus ⏳ during queued/drafting/approval/lookup/posting. Only the state changes to ✅ after posting, ⚠️ on failure/uncertain delivery, or ⛔ on rejection. If Slack rejects the missing dougle name, show only the state emoji. Detailed waiting stages remain in the DM card. These are last-known stages, not a live heartbeat or percentage. Initial DM and subsequent updates use the same card. Same thread resumes its session; a new channel-level mention is a separate session. Cross-thread conversation linking is not implemented.


Approval controls are Post / Edit / Reject; Reject acts immediately without a reason form. Needs-lookup cards have Submit lookup results / Reject. Manual Regenerate is removed; do not suggest it or invoke a legacy action. Existing queued work and automatic refresh after a changed thread still require a new approved draft. Avoid routine closing offers and boilerplate date/production caveats; retain only limitations material to the question. Explain to an adult colleague unfamiliar with the system, preserving necessary business precision.
