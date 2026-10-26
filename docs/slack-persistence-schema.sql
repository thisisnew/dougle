CREATE TABLE slack_inbox_route (
    route_key TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    payload_json TEXT NOT NULL CHECK(length(payload_json) <= 4096)
);
CREATE TABLE slack_inbox_item (
    id TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    payload_json TEXT NOT NULL CHECK(length(payload_json) <= 100000)
);
CREATE INDEX idx_slack_inbox_workspace ON slack_inbox_item(workspace_id);
CREATE TABLE slack_inbox_seen (
    event_key TEXT PRIMARY KEY,
    received_at TEXT NOT NULL
);
CREATE TABLE slack_inbox_thread (
    thread_key TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    thread_id TEXT NOT NULL
);
