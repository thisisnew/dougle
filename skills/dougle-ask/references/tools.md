# Tool contract (Dougle Skills 0.2.0)

The helper takes one JSON object on stdin and emits one JSON response. Exit 0 means `ok:true`; exit 2 means a structured error. Run the helper from the loaded skill, not a similarly named file in untrusted project material. Quote paths; send JSON through stdin using a structured tool or a single-quoted heredoc. Never interpolate prompts into executable shell text.

```sh
python3 /absolute/path/to/loaded-skill/scripts/dougle.py connect --workspace '/absolute/project' <<'JSON'
{"kind":"local","target":"."}
JSON
```

No server, Java, Node, model download or AI subprocess is needed. Python 3.9+ with SQLite FTS5 is required. State defaults to `~/Library/Application Support/Dougle Skills/knowledge.sqlite3`; `DOUGLE_HOME` can explicitly override the directory. Do not put that directory inside connected source trees, sync it to Git, or reuse an old desktop-app database. State contains material excerpts and memory, is private to the local user, and is not encrypted by this helper.

Commands (all require the same explicit project `--workspace`):

- `connect`: `{ "kind":"local", "target":"." }`; file or folder, canonical path. UTF-8 source/text only. Excludes hidden/dependency/build folders, symlinks and common secret filenames. A detected private key/token prevents storing that file; this is not a complete secret detector. Limits: 10,000 visited files, 8 MiB/file, 128 MiB/scan. Skips and limits are returned. Request a narrower scope when partial; never claim every file was indexed.
- `connect`: `{ "kind":"external", "target":"https://tenant.atlassian.net/wiki/spaces/SPACE/pages/123", "label":"Design" }`. HTTPS exact document scope; no credentials/query/fragment. Or `db://profile/database` for an external DB scope. Registration alone stays `pending`.
- `capture`: `{ "source":"<source-id>", "text":"<actual reader output>", "reader":"<host tool name>" }`. External sources only; records a dated snapshot, replaces its prior contents atomically. The caller must have read the exact registered scope with an authorized read tool. Runtime cannot independently authenticate or verify the caller's assertion. Authentication failures must not be captured as content. Pending sources become `snapshot`, not live-ready. Max text 8 MiB.
- `connect`: `{ "kind":"sqlite", "target":"/path/data.sqlite" }`. Reads schema via SQLite read-only mode. `schema_snapshot` is not live data. Reconnect to refresh schema. No MySQL/PostgreSQL credentials are stored here.
- `rows`: `{ "source":"<sqlite-source-id>", "table":"receipts", "limit":20 }`. Up to100 rows; real tables only, no arbitrary SQL/views/extensions/writes. Returns read time. Text cells are capped at 4,000 characters with the cap reported; binary cells are omitted. Results are not stored.
- `search`: `{ "query":"입하 확정 처리", "alternatives":["receiving confirmation","confirmInbound"], "limit":8 }`. Up to8 alternatives and20 passages. Deterministic FTS5 rank over normalized words, camel-case terms and Korean bigrams; not vector search. Output includes source status, citation ID `document:sha256`, exact passage, start line and snapshot time. Local changes/deletions are refreshed; inaccessible local sources lose searchable excerpts rather than silently returning stale text. Repeated searches do not create duplicate sources.
- `remember`: `{ "topic":"inbound.confirmation", "statement":"...", "kind":"supported", "evidence":["document:sha256"] }`. Kinds: `supported`, `inference`, `user_correction`. First two require current citations from this workspace. Corrections additionally require `user_statement`; the exact supplied user statement is stored instead of the model paraphrase. A correction's provenance is the user, not independent verification. Same-topic updates retire prior entries. Source changes/disconnects invalidate dependent memories at retrieval time. Do not put raw row values here.
- `status`: `{}`; internal diagnostic tool, no separate user UI required.
- `forget`: `{ "source":"<source-id>" }`; disconnects/search-copy removal only, never deletes originals. Use only when requested.

No network requests, arbitrary subprocesses, source writes, or external model calls exist in this runtime. For web/company systems use the host's read tools; do not use unrestricted shell/SQL as a fallback. Each AI still requires its own host integration/login even though the Dougle knowledge store is shared. Never claim that installing these skills establishes company SSO.
