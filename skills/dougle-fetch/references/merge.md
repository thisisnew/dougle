# Ordered claim merge — v1

Required by publish and fetch. Apply these rules to the selected knowledge package with the
host's existing tools. This is a decision procedure, not a separate command or merge runtime.
AI may identify candidate relationships; it must not replace the decisions below with a
fresh summary, author preference, upload time or a guess about which wording is better.

## 1. Freeze inputs and identities

Use B = last completely synchronized shared snapshot, L = current selected local claims,
and R = one pinned remote commit. Record these inputs in the local operation record before
editing. A missing/unreadable saved base is an error, not permission to pretend this is a
first import. A genuinely first import has no B. Resume a pending operation before starting
another one for the same binding.

Match by qualified repository/project/source/claim IDs and saved lineage mappings. Preserve
those mappings when publishing imported knowledge back to its origin. Names, file paths,
similar text, or an unqualified C01 alone do not establish identity. An ID that points to a
different source/scope is an identity collision: stop the package merge without active edits.
For valid unmapped new claims, allocate an ID once in the operation record and reuse it on
retry. Keep legacy records; assign missing IDs only to records actually being shared and
persist their mapping before mutation. Ambiguous legacy identity blocks that record and its
dependents; report the pending scope rather than guessing a mapping.

## 2. Compare records, not summaries

Core = claim meaning: applicability, conditions, exceptions, dependency IDs, provenance
class (observed/reported/inferred), and explicit withdrawal/supersession state, plus either
the complete source-checked `Fact-v1` slots in records.md or the exact `Statement`.
Use `Fact-v1` equality only when BOTH sides have complete blocks, the same qualified claim
identity/lineage, every statement qualifier is represented, and the changed projection's
statement and original evidence have been checked against its slots. For a remote claim
whose original is unavailable, a previously validated synchronized base may establish that
side's binding; otherwise use exact-statement comparison or preserve alternatives. A
`Fact-v1` declaration, terminology map, similar wording, or model judgment by itself is
not proof. A wording-only difference with equal verified cores keeps the existing stable
portable statement (prefer B, then R) and retains the other wording in local history; it
does not create a new business claim or a portable timestamp. If the wording adds a fact,
condition, exception or uncertainty absent from the slots, the core is not equal.
Compare structured fields when present; otherwise compare the entire claim body
conservatively. Only normalize CRLF to LF and field ordering. Normalize an alias to its
preferred term only with a verified scoped terminology mapping that preserves exact
meaning. Do not normalize numbers, negation, units, identifiers, dates, code, casing,
or unverified paraphrases. Unclear equality is unequal.

Evidence is a separate set keyed by original source identity, revision/observation, locator
and provenance. Equal cores may union evidence; conflicting evidence remains visibly
conflicting, never silently selected. Do not replace an older observation with a newly
imported timestamp. Local freshness/checkpoint data is never shared. Evidence omissions
alone do not delete another person's evidence. Explicit evidence withdrawal is recorded
with its reason and lineage, not erased. Old conclusions remain history, not current facts.

For comparisons below, equality is true only when BOTH cores exist and are equal. `*`
means irrelevant. A missing record means omission, not a deletion instruction. All rows are
mutually exclusive. Apply one row per matched claim; keep original evidence/history in every
case. Additions outside authorized disclosure scope and unresolved identity are handled
before this table, never widened automatically.

| B | L | R | L=R | L=B | R=B | Decision |
|---|---|---|---|---|---|---|
| 0 | 0 | 0 | * | * | * | NO_CHANGE |
| 0 | 1 | 0 | * | * | * | ADD_LOCAL |
| 0 | 0 | 1 | * | * | * | KEEP_REMOTE |
| 0 | 1 | 1 | 1 | * | * | SAME |
| 0 | 1 | 1 | 0 | * | * | CONFLICT |
| 1 | 0 | 0 | * | * | * | RETAIN_BASE |
| 1 | 0 | 1 | * | * | * | KEEP_REMOTE |
| 1 | 1 | 0 | * | * | * | KEEP_LOCAL |
| 1 | 1 | 1 | 1 | * | * | SAME |
| 1 | 1 | 1 | 0 | 1 | 0 | REMOTE_CHANGE |
| 1 | 1 | 1 | 0 | 0 | 1 | LOCAL_CHANGE |
| 1 | 1 | 1 | 0 | 0 | 0 | CONFLICT |

- NO_CHANGE: no record or write.
- ADD_LOCAL / KEEP_LOCAL / KEEP_REMOTE / RETAIN_BASE: preserve that core, with its original
  evidence/status. Retained omitted material is not silently certified as current.
- SAME: keep the common core, union evidence and history. Identical input is a no-op.
- LOCAL_CHANGE / REMOTE_CHANGE: use the single changed core, retaining B and its provenance
  as history. This detects a change; it does not prove the new claim is true. Preserve
  reported corrections as reported and imported changes as needing recheck.
- CONFLICT: retain B when present and BOTH alternatives under the stable claim ID, with
  their evidence, conditions and provenance. Mark unresolved, with no authoritative winner.
  Do not synthesize a third claim that combines possibly incompatible conditions.

## 3. Preserve uncertainty and stable output

Different IDs with equal verified core AND proven shared lineage may reuse the saved mapping;
otherwise keep separate claims. Suspected duplicates/contradictions across IDs get related
links and, when incompatible, an unresolved group. Never discard them on semantic similarity.
This conservative rule deliberately prefers a possible duplicate over lost knowledge.

Carry existing unresolved alternatives forward. Repeated receipt of one variant does not
resolve a conflict. Resolution needs a separate explicit correction or a verified original
that addresses the actual disputed conditions; record which alternatives it supersedes and
why. A new upload or timestamp is not such a resolution. If resolution is itself disputed,
keep the conflict. Ask must surface a relevant conflict or verify the original before giving
one answer; it must not present a conflicted value as settled.

Use stable ID ordering, then original revision/locator/core text ordering for variants and
references. Do not label identity by L/R: their roles reverse on another person's PC. Preserve
existing unchanged file bytes; serialize affected records consistently, without new import
or generation timestamps in portable content. Retain claim links after a file/topic move.
Record decision code, IDs, input revisions and resulting alternatives in the LOCAL operation
record; do not export this log or private notes. Validate all references/dependencies again.
If conflict representation cannot preserve the original conditions/links, withhold the
entire affected claim and dependent conclusions, keep existing remote records intact, and
report what remains pending. Do not silently omit a remote alternative.

## 4. Commit and synchronize the baseline

Publish directly to the user's designated knowledge branch after the sensitive-data gate.
Do not create a PR or auto-change repository protection. A branch requiring review blocks
direct publication; report that constraint. Use one checked-base commit with only the final
package diff and a normal fast-forward push, or equivalent expected-head atomic write.
A per-file SHA alone is insufficient to atomically replace several package files. An API
without a checked whole-package write cannot be used for this operation.

If remote head changes, read the new R and recompute once from the preserved local input,
then repeat semantic review and the full sensitive-data scan. If it changes again, leave
remote and checkpoints unchanged and report concurrent editing. Never force-push. If the
package already equals the merged result, skip the commit, verify that pinned remote tree,
and still finish any needed local reconciliation/checkpointing below.

After remote read-back succeeds, save the publication receipt separately. Reconcile the
same affected shared claims back into local notes, preserving private/unselected records
and local freshness settings, using fetch's backups and conflict-aware writes. Imported
remote changes need recheck. Advance the COMMON comparison base only after this local
reconciliation also succeeds. A successful push alone does not establish a common base.
Otherwise record remote_succeeded/local_pending; next invocation verifies that receipt and
finishes local reconciliation first, never repeats the push. If local notes changed during
the operation, recompute against the saved local snapshot and preserve those edits. Newer
local edits remain a delta against the acknowledged base, never folded into it as published.

Fetch never writes remotely. Advance its common base only when all local edits and reference
checks finish. Repeating either operation must preserve later local changes, unresolved
variants, original observation times and previously imported IDs.
