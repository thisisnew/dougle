---
name: dougle-reload
description: Refresh previously connected Dougle material and its dependent domain knowledge using the host's existing read tools. Use for reload or refresh requests targeting a project, source, service or all connected material; not for adding new sources or merely answering a question.
---

# Dougle reload

Always use respectful Korean 존댓말 in explanations, confirmations, follow-ups and errors. Do not mirror casual user wording; review every authored sentence before sending. Preserve clearly marked original quotes and code.

Use the current host AI's read/search/file tools. No runtime, server, background watcher or nested AI is needed. Read [the shared organization protocol](references/organization.md) for common-store discovery, source records, provenance and update boundaries.

## Resolve the requested target

Examples: `dougle-reload current project`, `dougle-reload Confluence receiving design`, `dougle-reload all` (including equivalent Korean requests).

Read the common index and registered legacy collection indexes and match registered sources by canonical identity, source ID, label, alias, path or requested service. Do not search the whole company or silently create a source. With no target or an ambiguous match, ask a short question listing relevant existing candidates. If nothing matches, explain that the material must first be connected; do not claim a successful empty reload.

"All" means all registered non-disconnected sources discoverable through the common store and its legacy indexes, including pending ones, within their existing authorized scopes. Briefly report the resolved target count/business scopes before processing. It never means unregistered company resources, every file on the PC or another user's store. Resume an old pending reload using its recorded target set; do not expand it merely because the common index now includes other collections. Only an explicit "current project" request uses the actual cwd to match a connected source scope; explain/clarify competing roots rather than guessing. A folder reload includes new, changed and removed files within its existing scope, excluding generated/dependency/credential material as before. It does not automatically include parent folders or linked external resources.

Freeze a short target list before reading. Process registrations/updates sequentially to avoid duplicate memory writers. For a large request, work in bounded batches and keep the exact remaining targets; do not silently reduce scope or claim completion for unprocessed sources.

## Refresh each source

1. Reread its current source record and dependent topic claims. Record an attempt separately from the last successful read; preserve the prior evidence/observation time.
2. Use an available authorized read tool on that exact source/scope. If the tool, login or permission is missing, explain the actual host setup step. Do not invent credentials, install connectors or bypass permissions. A failed check must not advance the successful-read timestamp.
3. Determine what changed using trustworthy revisions/content returned by the reader and prior evidence. Same title, size, mtime or successful HTTP response alone does not prove no change. For databases, refresh schema/business meaning; fetch current rows only when specifically needed and do not persist transient rows.
4. If a comparable revision/content confirms no change, report unchanged for the scope actually checked. Without a reliable baseline, revalidate relevant claims and report refreshed/rechecked; do not falsely classify the entire source as unchanged. Partial source coverage remains partial.
5. If contents changed, mark dependent claims needs_recheck, reread relevant passages, and update only justified claims/relations with new provenance. Inspect nearby affected definitions/callers when necessary within scope. Preserve unrelated topics and user-reported corrections. A contradiction stays explicit until resolved; a source refresh does not automatically override a user's statement.
6. Confirm deletion only from authoritative evidence (for example an explicit deletion response or a verified local absence). Some services mask denied access as 404; timeout, unauthorized, generic 404 or an incomplete listing is not sufficient. If deletion is confirmed, mark the source unavailable and its dependent claims unavailable/superseded; never delete the original or all memory automatically.
7. On access/temporary failure, keep the last snapshot and observation time but mark availability and dependent claims so they cannot be used as current evidence. On partial reads, retain scope/coverage gaps and leave unchecked dependencies needs_recheck. Do not interpret missing pagination results as deleted objects.
8. Before saving, reread files and reconcile concurrent edits using the host's conflict-aware tools. Write only Dougle memory. After successful reads and dependent-note persistence, update coverage-specific verification entries under the ask-time freshness policy; never stamp the whole source fresh after a partial read. If persistence is denied or interrupted, say which reads occurred and which updates were not saved. Do not label an unsaved reload completed.

## Report and resume

Give a brief per-source or grouped summary with counts: changed, unchanged (confirmed), refreshed without comparable baseline, partial, access/temporary failure, deletion confirmed, and not processed. Include important affected concepts and unresolved gaps, not a dump of the knowledge store.

Persist each source's completed update separately. On interruption, retain completed work and the remaining target list when permitted; never advance a store-wide freshness marker for unfinished work. A retry checks current records again, processes unresolved targets, and does not create duplicate sources. Do not promise automatic retries or monitoring after this conversation ends.

Reload is explicit maintenance and bypasses the interval even for recently checked sources; `dougle-ask` uses the shared ask-time freshness policy and always checks fresh evidence for current-state questions. A completed reload is an observation at a time, not a guarantee that the source cannot change later. Source code, company documents, databases and infrastructure remain read-only; the skill does not globally sandbox the host AI.

## Load task details before acting

Read the [organization core](references/organization.md) and follow its conditional reference triggers. Before source or topic memory writes read [record rules](references/records.md); before verification or verification-record updates read [freshness rules](references/freshness.md). For reload/publish/fetch also read [operation scope](references/operations.md). Do not load unrelated detail files or reread unchanged instructions still in active context. Never omit needed evidence or scope checks to reduce context.
