# Records detail

## Register without duplicates

The user need not know whether material is already connected. Before creating a record, search the existing source index and read relevant records, identify the source, and reconcile it. Registration in the common store is sequential; do not spawn parallel registration agents. Apply the overlapping-write requirements in [store rules](store.md) to records and shared indexes. Rereading alone is not an atomic uniqueness constraint against unrelated concurrent hosts.

Identity rules:

- Local: compare canonical absolute paths from the host filesystem tools. Resolve harmless path spelling differences without modifying files. Do not equate different files by basename. A requested child already inside a connected directory reuses that source and extends its inspected coverage; directory registration alone does not prove the child was read. A narrower independent scope is created only when explicitly requested. Do not silently expand scope when an ancestor folder is later requested.
- Confluence: compare the authenticated tenant/site identity plus page ID returned by the reader or a trustworthy canonical page URL. Titles and alternate share links are aliases, not separate documents. Resolve short/share links through the authorized reader; if identity cannot be resolved, record pending identity/access rather than guessing. Different tenants with the same page number are distinct.
- Notion: compare the reader-confirmed workspace/account scope and normalized page or database ID. URL titles, hyphenation and share-link spelling are not new identities when the reader confirms the same ID. Do not merge inaccessible resources by guessed IDs.
- Other web: use a reader-confirmed canonical URL when available. Remove only known non-content tracking parameters. Do not discard arbitrary query parameters or case-sensitive paths. Redirects need confirmation from the reader; do not infer them from similar titles.
- DB: compare the reader-confirmed server/instance, database and schema scope, without credentials. Connection profile names are aliases. If endpoint identity is hidden and equivalence is unknown, ask only the needed clarification; do not assume identical schema text means the same database.

Outcomes:

1. Same identity, unchanged revision confirmed by a fresh authorized read/check: reuse existing record. Report already connected.
2. Same identity, changed contents or freshness unknown: refresh that record within scope; mark affected claims needs_recheck until verified. Do not claim unchanged unless actually checked.
3. New identity: add one source record and update the index.
4. Identity/access unresolved: retain or add a pending record with the proposed locator and unresolved identity; do not label it connected. Repeated exact pending locators reuse the pending record.

Store canonical identity, input aliases, scope and identity confidence in source records. Content equality alone is not identity: preserve separate provenance for copies/mirrors unless the user explicitly asks to merge. When previously separate records are proven identical, choose a surviving source ID, preserve alias/history and update dependent note links without losing evidence. On a request overlapping a broader source, read the requested subset and report whether it was previously inspected or newly checked.

Examples: `./service` and its same resolved absolute path reuse a source; two Confluence titles pointing to the same tenant/page ID refresh one source; identical text at two different tenant/page IDs stays separate; matching DB profile names without endpoint evidence do not prove identity.

## Source record

Assign an unused record ID after checking both common and qualified legacy records. Record:

- Organization/account/tenant and project/service scope when established. Unresolved identity stays unknown; a folder basename is not a global project identifier.

- Exact requested location and scope: canonical folder/file, document URL, or non-secret DB profile/database name. Never save credentials, signed URLs, access tokens or connection strings.
- State: `pending_access`, `read`, `partial`, `unavailable` or `disconnected`.
- What was actually inspected, what was excluded, and the observation time. A sampled directory must say partial; do not claim all files were ingested.
- Revision/commit/version if the read tool provides it. Do not invent a fingerprint or pretend an mtime is proof of content identity.
- Reader/tool used and unresolved access gaps. A read is a snapshot; registration, login and reading are separate outcomes.
- Topic notes that depend on this source, enabling targeted rechecking.

Example (synthetic):

```markdown
# S001 — Receiving service
Location: /work/example-receiving
Scope: receiving module
State: partial
Observed: <actual observation time>
Read: README.md; src/ReceiptService.java; migrations/001_receipt.sql
Not read: other modules; generated build output
Related topics: ../knowledge/receiving-confirmation.md
```

Do not retain whole source copies by default. Save only necessary short evidence excerpts when permitted; otherwise use precise source locations and reread when needed. Do not treat access-denied pages as business content.

## Organize searchable topic notes

Use one coherent business concept/process per established system scope, not one summary per source file. Search known aliases before adding topics. Existing notes remain readable without this layout; no upgrade-time rewrite or new identity is required.

For NEW notes and the parts being genuinely refreshed, put a compact navigation card at the top: topic ID/title, project/service scope, established Korean/English aliases and code/API/table identifiers, one-line subject description, and a section map from likely subquestions to exact existing heading/claim IDs. Include a visible pointer to exceptions/conflicts/unknowns and their claim links even when details live elsewhere. Keep a Navigation state (complete or needs_rebuild) with the covered claim/section IDs; this is an advisory completeness marker, not source verification. This card is navigation, not authoritative answer text or a second copy of all facts. Do not invent aliases or a supported relationship just to improve matching.

A card may exclude unread sections only when a trusted validation receipt binds that exact map to the current note body: an existing host-computed digest of all content outside the card paired with the exact validated map, or a host-provided immutable snapshot receipt for the map and body actually validated together. Do not hash the card into its own digest. A declared revision, timestamp, mtime, matching headings or `complete` alone is insufficient; an older writer can add an exception under an unchanged heading. Missing/mismatched binding uses retrieval.md's full-candidate fallback. Check only relevant notes with available host tools; never require whole-corpus hashing, migration, Python or a new runtime. A valid body binding does not validate linked targets or original-source freshness.

Keep each useful answer-sized unit under a stable descriptive heading with its claim ID. Store statement, provenance, source/precise location/revision/actual observation time/freshness, applicable scope and preconditions, exceptions, linked dependent claims and unresolved conflicts together or through explicit links. Each relationship edge needs its own evidence. Preserve exact identifiers; a function and table co-occurring is not proof of a write.

Classes: observed = directly supported by an actually read source; reported = user statement/correction; inferred = interpretation with supporting clues. Freshness: current_at_observation, needs_recheck, unavailable, superseded. Observation is not live certainty.

Synthetic layout (names and evidence illustrative only):

```markdown
# T01 — 입하 확정
Navigation: needs_rebuild
Covered sections: C01; C02; 확인할 점
Scope: receiving
Aliases: 입고 확정; confirmReceipt
About: 입하 확정의 조건과 기록 흐름
Sections: 저장 위치 -> C01; 취소 조건 -> C02
Cautions: C01 requires C02; unresolved items -> 확인할 점

## C01 — 저장 위치
Statement: <behavior actually established>
Provenance: observed
Evidence: S001, src/ReceiptService.java, confirmReceipt(), <verified revision>
Observed: <actual read time>
Freshness: current_at_observation
Requires: C02 — 취소 조건

## C02 — 취소 조건
Statement: <verified guard and consequence>
Evidence: <exact supporting location and observation>

## 확인할 점
<actual gaps/conflicts, or none established; never guess>
```

Do not force every note into empty headings. Keep short notes intact. For long notes prefer a section map and passage reads first; split only when genuinely distinct subtopics warrant it AND affected source/index/cross-note/sharing references can be preserved. Keep existing topic/claim IDs and relative evidence identities stable. Before any split record old-to-new locations, preserve backward pointers for old locators and verify dependent links; interrupted/unsafe edits must leave the original usable. Do not make all existing records migrate to enable ask/publish/fetch. Formatting/navigation additions alone do not advance observation or verification times.

Keep facts, reported corrections and inference distinct. Do not save hidden reasoning, full chats, secrets, employee/customer records or transient DB rows as durable knowledge.

## Updating and correcting

- Apply the ask-time freshness policy in freshness.md before relying on a source. A current-state question always requires a fresh authorized read. No background watcher exists.
- If content changes, mark claims relying on the affected passages `needs_recheck`; update them only after rereading. Do not automatically invalidate unrelated topics.
- If the source disappears/access fails, mark its availability and dependent claims; retain provenance but do not use them as current evidence. Failed refresh must not turn a cached note into a fresh read.
- For a user correction, record their actual statement as reported, identify which previous claim it changes, and mark the replaced claim superseded. Do not erase independently observed contradictions. Explain an unresolved conflict when relevant to the answer.
- Merge duplicate topics cautiously; retain claim IDs/evidence and update source/topic index links. Avoid silently rewriting unrelated notes.
- Disconnect requests mark the source disconnected and prevent its dependent claims being used as current facts. Delete only Dougle records if the user explicitly requests forgetting/deletion; never delete business originals.
- Save task handoff context under an explicitly scoped context ID only if useful: user goal, established findings with claim/source links, unresolved questions and next read. No full session transcript. Do not assume a new AI has prior chat context.


## Patch affected knowledge, not the entire note

When connecting, refreshing or merging, read affected claims and their dependencies completely, then patch only those sections. Preserve untouched statements, exact identifiers, corrections, provenance, prior observation times and unresolved conflicts. Invalidate claims dependent on changed evidence; deletion and access failure are different observations.

Use store.md's real conditional-write/lock/single-writer protection for overlapping edits. Under that protection, mark affected navigation `needs_rebuild` durably before changing a claim, dependency or split. Patch content, rebuild section/caveat pointers, and validate the map against the complete affected note body before marking it complete. Record a body binding only if the host can establish it reliably; otherwise keep the card advisory and use full-candidate reads. A failed marker write prevents the update. Conflicts or lost ownership stop dependent writes and require reconciliation; an interruption leaves readable content and pending navigation, not a completed checkpoint.

Save note edits before section/index pointers, validate affected incoming and outgoing links under freshness.md, and report partial work. Per-file CAS does not make this sequence an atomic multi-file transaction. Do not replace a whole topic with a new summary that erases caveats. Formatting/navigation changes alone never advance source observation or verification times.
