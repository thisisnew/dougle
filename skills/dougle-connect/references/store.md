# Store detail

## Locate the common user store

1. Resolve the current user's home with the host's filesystem/environment tools. Every Dougle invocation uses the same `~/.dougle/` store and `~/.dougle/index.md` regardless of the process working directory, repository or provider. Do not ask the user to create/select a workspace, derive a memory partition from cwd, or silently chdir to another project.
2. Read the common index; if it does not exist, discover existing legacy indexes as below and create it without overwriting other files when persistence is permitted. New notes go under this store's sources/ and knowledge/. Project/service/account/tenant identity is metadata attached to sources and topics, not a directory prerequisite for using Dougle. No source connection is inferred from starting the AI in a directory.
3. Resolve relative source requests such as `./service`, "current folder" or "현재 프로젝트" against the actual caller's cwd only when that location is explicitly requested. Once connected, keep its canonical original locator. Starting ask elsewhere neither reinterprets saved relative paths nor changes the set of searchable knowledge. Bare questions use their explicit subject and current conversation context; clarify only when materially different systems could answer them.
4. Both Claude and Codex use this store; installed skills stay in their host locations. Do not edit source projects, AGENTS.md or CLAUDE.md. Existing host sandbox/file/service permissions remain in force. If the store cannot be accessed, explain the missing access and continue only with permitted evidence; do not bypass restrictions or create a per-cwd fallback store. If the store is readable but not writable, search it read-only and report unsaved changes when relevant.
5. Before memory edits, reread affected records and reconcile concurrent changes with the host's conflict-aware tools. Create new files/IDs without overwriting existing ones. Keep writers sequential; this is not a global lock or atomic database.

Layout (plain Markdown):

```text
~/.dougle/
  index.md              common topic/source/legacy index and default policy
  sources/<source-id>.md canonical identity, business scope, read history
  knowledge/<topic-id>.md scoped concepts and claims with provenance
  sharing/<binding-id>/  repository/package/scope binding and merge state
  contexts/<context-id>.md optional explicitly scoped task handoff
  workspaces/<legacy-label>/ preserved earlier records and bindings
```

Read the index and relevant notes only; do not load all knowledge on every question. A global index makes sources discoverable, not interchangeable. Preserve organization/account/tenant, project/service and authorized scope with each source, claim and index pointer. Separate same-named concepts from different systems; link them only with supporting evidence. An explicit project restriction filters retrieval even though all records live in one store. Never resurrect disconnected/deleted-memory entries through index rebuilding.

## Existing knowledge without destructive migration

Discover ordinary `workspaces/<legacy-label>/workspace.md` indexes inside the user store, including when an existing common index is missing legacy entries. Treat their canonical roots as original-source metadata, not a filter against the current cwd. Register each as a legacy collection pointer in index.md and consult its topic/source index when relevant. Do not bulk read, move, delete, flatten or rewrite old notes just to enable discovery. Paths in this registry must resolve inside the store; do not follow a symlink or traversal to an unrelated location as memory. Authorized original source locators are separate from these memory pointers.

Keep each legacy record's identity qualified by its collection and original relative record path. `S001` in two old collections is not the same record, and a new global `S001` must not overwrite either. Preserve old relative evidence links, claim IDs, corrections, timestamps, disconnected status and sharing checkpoints. Search source identities across both common and legacy records before registering duplicates. When the same original is proven to occur twice, select an existing authoritative record, keep alias/provenance pointers and reconcile conflicts before reuse; do not erase either history simply because the index is common. Existing notes can be refreshed in place; new unrelated sources use the common directories.

Preserve explicit legacy refresh defaults as collection-scoped inherited preferences, rather than choosing one old workspace's default for everybody. A per-source preference overrides this inherited value, then the global default applies. A user can explicitly replace these inherited defaults for all stored material; do not broaden a single-source preference request.

Legacy sharing bindings remain tied to their original repository/package and selected source scope. Reuse them through a qualified pointer or a verified non-destructive copy, preserving base/commit checkpoints; never broaden an old export to the whole store. Never import an old context.md as the new conversation's active subject just because it was modified most recently. Use old task context only when the user or current conversation identifies that task.

Index registration is incremental and repeatable: add only missing collection/record pointers, do not advance source observation/freshness times, and preserve completed work if interrupted. Inaccessible/malformed indexes are partial discovery, not proof of an empty store; disclose gaps when they affect a question or reload-all. Do not claim legacy conversion is completed without reading the relevant indexes. Older SQLite stores are handled by the existing read-only policy below, not silently migrated.


## Compatible topic navigation

Navigation cards and stable claim-section maps are optional enrichments to existing Markdown notes, not a new storage format. Keep reading old common and legacy layouts through scoped search/full-note fallback. Installation or skill update never moves/rewrites the user's knowledge. New notes use the enriched form; update old notes only during their actual scoped save/refresh. Existing sources, IDs, relative evidence and sharing bindings survive. No permission to write means no enrichment, not no search.
