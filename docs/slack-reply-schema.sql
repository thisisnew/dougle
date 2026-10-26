CREATE TABLE slack_reply (
    id TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    inbox_item_id TEXT NOT NULL,
    preview_request_id TEXT NOT NULL,
    scope_hash TEXT NOT NULL,
    scope_json TEXT NOT NULL CHECK(length(scope_json) <= 4096),
    body TEXT NOT NULL CHECK(length(body) BETWEEN 1 AND 4000),
    digest TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('PREVIEW','CANCELLED','EXPIRED','SENDING','SENT','FAILED','UNCERTAIN')),
    execute_request_id TEXT,
    dispatch_count INTEGER NOT NULL DEFAULT 0 CHECK(dispatch_count IN (0,1)),
    slack_message_ts TEXT,
    failure_code TEXT,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE(workspace_id, preview_request_id),
    UNIQUE(workspace_id, execute_request_id)
);
CREATE INDEX idx_slack_reply_inbox ON slack_reply(workspace_id, inbox_item_id, created_at);
