# Slack inbox runtime contract

The parent-owned unreleased v27 migration installs the four tables in
`slack-persistence-schema.sql`; no Slack constructor runs DDL.

- Routes (100), current message records (200), event/removal tombstones (2,000,
  24 hours), and Slack-thread/conversation links (1,000) persist in SQLite.
  Capacity exhaustion refuses new events rather than evicting unsent messages.
  Workspace deletion cascades routes, items and thread links. Event tombstones
  remain until expiration to prevent retry delivery to another workspace.
- Receiving commits before Socket Mode acknowledgement. Current revisions are
  compared numerically; late originals cannot replace corrections or deletions.
  Inbox order uses the original Slack timestamp, not arrival order. This is the
  latest received revision, not a complete revision archive or history backfill.
- Explicit send saves a question only, never starts AI. The saved question,
  receipt and thread mapping share the conversation creation transaction. Same
  Slack team/channel/thread plus Workspace reuse one conversation; identical text
  in separate threads does not merge. Existing conversation busy/parent/retention
  restrictions still apply. Missing retained conversations are not silently
  recreated. Slack edits/deletions do not rewrite already saved questions.
- Tokens are memory-only and require new explicit setup after application
  restart. During one opted-in session, disconnections allow at most three
  reauthentication/reconnect attempts (1, 2, 4 seconds); explicit disconnect
  cancels pending work and ignores late callbacks. Authentication/permission
  failures stop retrying. Received uninstall/revocation or bot membership-loss
  events stop the connection conservatively. No channel scope/access is inferred
  from Socket Mode or `auth.test` success: status reports `NOT_CHECKED` unless a
  loss was observed. Offline/missed events are not fetched, so `gapPossible=true`.
- `GET /api/slack/workspaces/{workspaceId}/inbox-count` returns only
  `{state,count}` for unsent, non-deleted items. It performs an aggregate query,
  not a message-body fetch. `SlackInboxIndicator` takes `workspaceId`, polls the
  local endpoint every 15 seconds without overlapping requests, and renders a
  noninteractive badge only for `CONNECTED` and a positive count. No OS prompts.
- `SlackPanel` mount props are unchanged. Admin/token instructions are collapsed
  under advanced setup. Browser OAuth registration, company app installation,
  real credentials and real Slack acceptance remain external blockers. Mocked
  provider tests do not establish live E32/E33/E34 acceptance.

Focused backend tests: `SlackInboxServiceTest`, `SlackTransportTest`,
`SlackInboxIntegrationTest`. Frontend: `SlackPanel.test.tsx`,
`SlackInboxIndicator.test.tsx`. Do not run Maven concurrently with the parent.
