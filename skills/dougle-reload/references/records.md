# Records detail

## Register without duplicates

The user need not know whether material is already connected. Before creating a record, read the existing source index and relevant records, identify the source, and reconcile it. Registration in the common store is sequential; do not spawn parallel registration agents. Reread before saving. This procedure is not an atomic uniqueness constraint against unrelated concurrent hosts.

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

## Organize domain knowledge

Use one note per coherent business concept/process and its established system scope, not one unstructured summary per file or one merged note for homonyms across systems. Search existing notes for synonyms before adding a topic. Each note should contain only useful, supported information:

- **Concept:** business meaning and terminology/synonyms, including code identifiers when known.
- **Rules and flow:** preconditions, state transitions, invariants, exceptions and responsibilities that were actually established.
- **Relationships:** document → API → function → table/event, with evidence for EACH edge. An endpoint and a table occurring in the same repository do not prove a call/write relationship.
- **Claims:** stable claim ID, concise statement, provenance class, evidence locations, observation time and freshness state.
- **Open questions/conflicts:** unknown links, alternative interpretations, or divergent sources.

Claim provenance:

| Class | Meaning |
|---|---|
| observed | Directly supported by a cited source that was actually read |
| reported | A user's statement or correction, not independently verified |
| inferred | An interpretation with supporting clues, not a proven fact |

Claim freshness: `current_at_observation`, `needs_recheck`, `unavailable`, `superseded`. Even current_at_observation is NOT a guarantee of live correctness. Evidence includes source ID and precise URL/section or file/identifier/line, plus revision if available. Use relative links between notes so the memory directory can be moved without breaking internal references.

Example claim:

```markdown
## C01 — Confirmation persists a receipt
Provenance: observed
Freshness: current_at_observation
Evidence: S001, src/ReceiptService.java, confirmReceipt(); migrations/001_receipt.sql
Observed: <actual observation time>
Statement: <only the behavior established by these source reads>
```

Keep observed facts, reported corrections and inferred relationships distinct within a topic. Do not fill empty headings with guesses. Do not save hidden reasoning, full chats, secrets, customer/employee records or transient database values as durable domain knowledge.

## Updating and correcting

- Apply the ask-time freshness policy in freshness.md before relying on a source. A current-state question always requires a fresh authorized read. No background watcher exists.
- If content changes, mark claims relying on the affected passages `needs_recheck`; update them only after rereading. Do not automatically invalidate unrelated topics.
- If the source disappears/access fails, mark its availability and dependent claims; retain provenance but do not use them as current evidence. Failed refresh must not turn a cached note into a fresh read.
- For a user correction, record their actual statement as reported, identify which previous claim it changes, and mark the replaced claim superseded. Do not erase independently observed contradictions. Explain an unresolved conflict when relevant to the answer.
- Merge duplicate topics cautiously; retain claim IDs/evidence and update source/topic index links. Avoid silently rewriting unrelated notes.
- Disconnect requests mark the source disconnected and prevent its dependent claims being used as current facts. Delete only Dougle records if the user explicitly requests forgetting/deletion; never delete business originals.
- Save task handoff context under an explicitly scoped context ID only if useful: user goal, established findings with claim/source links, unresolved questions and next read. No full session transcript. Do not assume a new AI has prior chat context.

