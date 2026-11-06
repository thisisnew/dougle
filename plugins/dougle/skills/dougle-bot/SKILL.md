---
name: dougle-bot
description: 'Set up and manage the optional local Dougle Slack bot: start, status, stop or add a channel. Uses a separate local runtime and Keychain credentials; never posts a reply through the host AI or bypasses the designated Slack approver.'
---

# Dougle bot

Use respectful Korean 존댓말 for all guidance and failures. Keep instructions short and actionable. This skill manages the optional runtime; it does not implement timers, answer Slack inquiries itself, or replace dougle-schedule.

Run the bundled `scripts/bot.sh status` using its absolute path first. Resolve paths relative to this installed skill, never the current project. A missing runtime/config is first-run setup, not a connection failure. Read [setup](references/setup.md) only when setup, missing credentials or channel registration requires it. Never read token values, user memory, the bot DB or provider credential files with host tools. Use runtime status/doctor output instead.

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

- **1 시작:** run `scripts/bot.sh start`. Report success only if runtime reports healthy=true and state=running. If still starting, poll status briefly; surface setup/connection failure honestly. Starting does not approve any reply. The user explicitly authorizes receiving future mentions by choosing start.
- **2 상태 확인:** run `scripts/bot.sh status`; show process/connection state, registered channels and pending counts. A saved configuration or live process alone is not a connected Slack listener. Status does not start anything.
- **3 중지:** run `scripts/bot.sh stop`. Report stopped only when confirmed; if stopping, show that ongoing work is closing. Preserve credentials/configuration/history/drafts. New mentions received while stopped are not imported on restart. Previously accepted unfinished inquiries remain recoverable. A request already sent to Slack may be unresolved; never promise to undo it.
- **4 채널 추가:** ask for the Slack channel URL or ID. Existing same-app Keychain credentials are reused. If credentials are missing, guide the `security add-generic-password ... -w` commands in setup; never collect token values in chat. If running, explain that channel changes require a short stop and let the user choose whether to stop now. Then run `scripts/bot.sh channel-add CHANNEL`. Guide `/invite @dougle` in that channel if needed, and use `doctor --online` to verify read access. Show the menu; do not silently restart. Adding a channel never broadens to every channel.

## Boundaries and errors

Tokens are entered by the user directly into a real terminal's Keychain prompt. No token argument, environment variable, file or chat paste. If an error occurs, show the safe error code and the next relevant action; do not dump raw logs or DB contents. Refer to setup for prerequisites. Do not uninstall or replace users' Claude installations or shell aliases.

The bot invokes installed dougle-ask and i-have-adhd on each engine call, including resume; successful tool results are checked. The bot prepares a DM draft; only the configured approver's exact latest-card approval may post. Never use the host Slack MCP to simulate that approval or post on the runtime's behalf. Failed style checks do not produce an approvable answer. A token/PII scan and known Korean ending checks are not guarantees of factual accuracy or complete sensitive-data detection.

The first preview is serial and local. It requires a running Mac, authenticated Claude CLI with Dougle installed, and authorized Slack app access. No auto-start on login, stopped-period catch-up, secret migration, automatic SQL execution, or OS-wide sandbox is implied. Engine hook/tool policy is an additional runtime boundary; use read-only company credentials. If the company restricts app installation, stop and explain the needed company approval.
