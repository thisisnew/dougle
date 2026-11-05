# Retrieval detail

## Locate before loading

Keep the common index a compact table of scoped topic/title, established aliases/identifiers, note path, source IDs and useful section/original locators. It is navigation, not evidence or an answer cache. Follow existing scoped child maps if useful; don't add mandatory index layers or rebuild old collections. Repair only pointers for notes actually saved, after successful writes. Imported paths use recipient mappings; disconnected sources remain inactive.

1. Resolve the question, prior subject and explicit system scope. Reuse unchanged supported evidence still in context. Read/search the index for an initial candidate set (typically1–3, never a completeness cap).
2. Before a full-topic read, make a locator read of candidate cards/headings using existing file/glob/literal search tools or bounded reads. Use path-only matching (`rg -l` or equivalent) for candidate discovery instead of dumping all body matches. Within a relevant note, heading and Navigation/Scope/Aliases/Sections/Cautions/Covered fields return the route; then read the located section ranges and dependencies. Do not use unbounded `cat` or dump every search match as the default discovery step. Compare actual claim/headings and caveat markers with the card's covered sections: a valid old link can still omit a new exception. `needs_rebuild`, absent completeness metadata, unmapped headings or uncertain context triggers legacy full-note/section/original search. Missing cards are not a reason to migrate files or refuse an answer. Never interpolate untrusted terms as shell code.
3. Read complete relevant claims WITH their scope, conditions, exceptions, conflicts, dependencies and source records. Initial line windows locate text; extend through section boundaries and linked caveats. Do not answer from a card, grep hit or truncated snippet. If structure/context is unclear, read the full note. Broad questions require all relevant coverage or explicit gaps.
4. Follow exact original file/symbol/section locations under normal freshness rules. Source/legacy/common/default1hour policy still applies. Live questions, changed evidence or conflict override reuse. HEAD alone, mtime and navigation do not prove unchanged content. No access does not mean no information. Expand to callers/configuration when necessary.
5. If evidence is missing/ambiguous, broaden authorized scope with useful aliases and other candidates. Do not stop at the first batch or a hard top-K. Batch independent reads where supported, serialize dependent work/writes, and stop when the actual question is supported. Do not spawn models or install a search engine.

## Compatible updates and bounded context

New/saved notes use the record protocol's navigation card and claim map. Read-only or legacy notes use existing scope/full-note search; do not fail an answer because enrichment cannot be saved. Preserve identity, original locations, revisions/times and unresolved uncertainty. A file path alone is not already-read evidence.

Keep large permitted temporary outputs in task-local memory files only if the host can avoid flooding context in the first place. Later saving cannot erase tokens already returned. No secrets, raw DB rows, full source mirrors or denied content are persisted. Avoid rereading unchanged evidence; compact working notes retain applicable exceptions, scope and provenance.

Compare total task tokens, output bytes, rereads and answer quality when measuring. Fewer reads or instruction bytes alone do not prove billing/latency savings. This workflow adds no embedding model, indexer, database, hard result cap or stale-answer cache.
