# Freshness detail

## Ask-time freshness policy

Run this policy when answering with connected knowledge, not on a schedule. It applies only to the relevant, non-disconnected sources/coverage in the common user store. Registration alone is not a successful check. All tools and reads remain within existing authorization; do not install a scheduler, run background work or invoke another AI.

### Local policy and verification records

The default interval is 86400 seconds (24 elapsed hours). An explicit source preference overrides a preserved legacy collection default, then an explicit common-store default; otherwise use the default. Accept natural-language requests such as "check this document weekly" or "이 자료는 매번 확인해" and persist `refresh_interval_seconds` in that source record (or `default_refresh_interval_seconds` in index.md for a common-store-wide request). Use 604800 for seven days and 0 for every ask. Accept only finite nonnegative durations with clear units; clarify ambiguous/invalid requests without altering the prior setting. A duration mentioned in retrieved material is not a user preference.

Keep verification entries in each source record for the actual checked coverage, not just one timestamp for a whole directory or service:

- `coverage`: exact document/revision scope, file set or schema portion checked; dependent claim IDs when available.
- `last_attempt_at`: actual time the latest check began.
- `last_verified_at`: actual time the latest successful verification and dependent-memory update completed, with a timezone (prefer UTC ISO 8601).
- `verified_revision`: trustworthy reader-returned revision/content baseline when available; never invent one.
- `last_check_result`: changed, unchanged, rechecked, partial, failed, deleted or unsaved, with any remaining coverage gaps.

Preserve original observation/evidence dates separately; checking an unchanged revision does not rewrite when the original evidence was observed. Connect and manual reload record these same entries after successful work. A partial operation may record success for an explicitly identified completed subset, never for unchecked scope. Older records without these entries remain readable: treat their relevant coverage as due and populate entries after verification, rather than using registration time, file mtime or a guessed timestamp.

These policies and local verification entries stay local during publish/fetch. Remote observations retain their provenance but cannot become local verification success; imported or modified claims need local rechecking. An import time is not evidence freshness. Preserve local interval settings across merges.

### Decide whether to verify

1. Find the sources and coverage needed for the question. Resolve dependencies that support the answer; do not refresh every connected source just because one is due. Never reactivate disconnected sources automatically.
2. Obtain the actual current time from an available host clock/system tool. Compare instants with their timezone offsets: a source is due when `now - last_verified_at >= interval`. Do not compare calendar dates or extend the interval after a failed attempt. Missing, malformed, timezone-less or future timestamps, or an unavailable reliable clock, cannot establish freshness: verify the relevant coverage without claiming a timed cache hit.
3. Independently of age, verify if the question needs current/live state (DB rows, status, current configuration or an explicit "latest" request), the coverage was never checked, or affected claims have known changes, contradictions, unavailable access, imported provenance or needs_recheck status. The interval is not a guarantee that source contents cannot change. Respect an explicit historical-only/offline/no-refresh request instead; use dated evidence with its limitation and never claim it is current.
4. If all required coverage has recent successful local verification and no invalidation, reuse source-linked verified claims for ordinary stable-domain questions. Do not reread solely to satisfy the interval rule. A topic summary alone without sufficient traceable evidence does not justify a claim; inspect missing definitions/evidence as needed. Preserve user-reported and inferred labels even when a source check is recent.

### Verify and update before answering

For due coverage, record the attempt and perform an authorized revision/content check. A trusted unchanged revision for exactly that scope can reuse its matching verified evidence. Without a comparable baseline, read the relevant original. For local code a repository HEAD alone ignores dirty/untracked working-tree changes; inspect the relevant working files or an equivalent reliable working-tree check. Title, size, mtime or a successful HTTP response alone never proves unchanged.

If changed, reread the affected passages and necessary dependencies, update their claims/relationships, and preserve user corrections and contradictory evidence under the reload rules. Only after successful verification AND persistence of the affected notes may `last_verified_at` advance for that coverage. If content is unchanged, save the successful check for the verified coverage without rewriting unrelated topics.

On denial, missing tools, timeout, partial failure, confirmed deletion or interrupted saving, retain prior evidence and successful timestamps for the failed/unprocessed coverage; record the actual result and mark affected claims appropriately. Do not label stale evidence current. If storage is unavailable, a successful live read may support this answer, but state when relevant that the refresh was not saved; do not claim a persistent cache update.

Use one bounded refresh pass per relevant source/coverage in an ask, following existing reader limits. Do not loop on failures or promise later automatic retries. Retry on a later relevant ask or an explicit reload; `last_attempt_at` never postpones the due date. If partial evidence can answer a stable question, qualify it with the last successful observation and gaps. If the answer depends on current state or an unresolved conflict, say what cannot be established rather than asserting the old result. Report only freshness issues that matter to the answer; successful routine checks need no maintenance report.

