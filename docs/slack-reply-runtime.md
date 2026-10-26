# Historical explicit Slack replies (superseded, unreleased v28)

**Superseded on 2026-10-26:** the latest user policy forbids all external business-system mutations, including Slack posting even with explicit approval. Remove/block outbound UI and backend dispatch; preserve existing receipts for scoped read-only access. No preview, execute, uncertainty acknowledgment or restart may re-enable posting. [Current policy and release hold](external-readonly-policy.md).

Everything below describes the former outbound-capable implementation, **not current permission, requirements or usage guidance**. Keep it as historical design/receipt evidence; its mocked tests do not satisfy the replacement E35 zero-transmission criterion. Do not install/publish the old 0.1.21/675a7c59 snapshot. Read-only enforcement must be separately implemented and verified; this documentation does not assert that work complete.

`SlackReplyPanel` is mounted beside inbox items. Mounting never loads an answer,
creates a preview, or posts. Opening the panel explicitly reads local receipts.
The user types a body (1–4,000 UTF-16 characters), previews the exact human text
and team/channel/root-thread target, then separately approves one external post.
No live Slack posting or credentials were used to implement or test this path.

## Persistence and recovery

The v28 migration is embedded in `DatabaseConfiguration.java`; the schema document
`slack-reply-schema.sql` is not a runtime dependency. Scope includes the
Workspace, inbox item, source revision digest, team/channel/root thread, bot
identity and a process/session identifier. Tokens remain memory-only. Reconnect,
route changes, edits, deletion and mismatched Workspace invalidate old scope.

The service commits a conditional PREVIEW → SENDING claim and unique execute
request ID before the HTTP call, then releases its database transaction. A replay
reads the receipt instead of posting again. The parent startup migration marks
SENDING as UNCERTAIN before the application is ready; it never re-executes work.
The parent Workspace deletion guard rejects SENDING receipts atomically.

Cancellation can change only PREVIEW, with zero dispatches. Once the claim wins,
cancellation is not message recall. Disconnect/revocation is checked again just
before HTTP; it cannot recall a request already dispatched. A crash between claim
and HTTP is conservatively uncertain. `dispatchCount=1` therefore denotes a
reserved/possible attempt, not proof of delivery. Local preflight rejection sets
it to zero. The bounded receipt cap is 1,000; records are not silently evicted.

UNCERTAIN or SENDING for an inbox item blocks another fresh dispatch. Before a
fresh preview after uncertainty, the user must independently inspect Slack and
explicitly acknowledge duplicate-post risk. This changes only the durable
`failure_code` to `UNCERTAIN_ACKNOWLEDGED`; the old status stays UNCERTAIN and the
old request cannot resend. It does not assert success or establish exactly-once
delivery. No automatic history lookup or additional permission request occurs.

All mutation endpoints reject unknown/duplicate fields, trailing JSON, coerced
booleans and bodies over 32 KiB. They are under
`/api/slack/workspaces/{workspaceId}/replies`: preview, `{id}/execute`,
`{id}/cancel`, `{id}/acknowledge-uncertain`. GET by ID and bounded GET by
`inboxItemId` read durable receipts. Cancel requires `{}`; acknowledgement requires
`{"acknowledged":true}`. Receipts remain subject to Workspace ownership.

## Provider constraints

Only `https://slack.com/api/chat.postMessage` is allowed, using the existing bot
token with `chat:write` and its existing channel access. Missing scopes, denied
membership and authentication rejection fail clearly; the app does not join
channels, expand scopes or impersonate a user. Transport disables redirects and
retries, bounds responses at 64 KiB and has a 15-second cancellation deadline.

Explicit `<@...>` and `<!...>` notification syntax is rejected before preview.
The wire payload escapes `&`, `<`, `>`, disables `mrkdwn`, uses `parse:none`, and
sets `link_names`, `reply_broadcast`, `unfurl_links`, `unfurl_media` to false.
The preview retains the exact human text; only Slack control-character encoding
changes on the wire. See Slack's [escaping and formatting rules](https://docs.slack.dev/messaging/formatting-message-text/).

Success requires `ok:true`, the approved channel, a valid message timestamp, and
an explicitly matching `message.thread_ts`. Missing/wrong thread data, malformed
responses, transport errors and ambiguous server errors remain UNCERTAIN—not
FAILED—because delivery could already have occurred. Slack documents that
`internal_error` and `fatal_error` may follow partial success. The method's
argument table and official SDK types do not establish an exactly-once guarantee
for `client_msg_id`; this implementation does not send or rely on it. See the
[method reference](https://docs.slack.dev/reference/methods/chat.postMessage/) and
[official SDK types](https://github.com/slackapi/node-slack-sdk/blob/main/packages/web-api/src/types/request/chat.ts).

Tests use synthetic data and mocked HTTP/provider boundaries. They do not count
as live E35 acceptance, organization installation, or browser OAuth completion.
