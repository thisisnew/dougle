---
name: dougle-publish
description: Publish selected curated Dougle domain knowledge to a user-designated GitHub repository, preserving provenance and remote changes. Use for explicit knowledge sharing or push requests, not for publishing original code, raw data or the user's whole memory directory.
---

# Dougle publish

Read [the organization protocol](references/organization.md), [the sharing protocol](references/sharing.md) and [the required sensitive-data gate](references/sensitive.md). Use the host's existing tools; do not install a runtime or generate an uploader.

Open the common user store and resolve the requested repository and selected business/source scope independently of cwd. If scope is not established and several projects/services could be exported, ask which material to share; a repository alone never authorizes exporting the whole common store. Legacy bindings retain their old export scope. A saved binding can be reused only within the user's intended scope. If no target exists, ask for a repository; never infer the Dougle software repository or a connected source repository as the destination. Check repository identity, visibility and authenticated access using available host tools. Missing credentials/tools require host setup guidance, never tokens pasted into chat.

Prepare a concrete scoped export of curated concepts/rules/relationships, corrections and evidence. Exclude private/local-only material and original bulk files as specified by the sharing protocol. Show the target branch/path, included topics, excluded material and size before the write. Existing authorization to publish the prepared scope to a private target is sufficient; public/unknown visibility or expanded disclosure needs explicit confirmation of the concrete export.

A sensitive-data finding or incomplete/failed check blocks the entire publish, including private targets. Do not auto-redact and continue or claim a pass from reading instructions. Complete staged-content semantic review AND run the bundled local shell scanner successfully for the exact final package immediately before upload. A changed stage invalidates the previous review/receipt. If a shell/tool is unavailable, do not publish.

Reconcile existing remote knowledge before committing. Preserve teammates' additions and local user corrections. Record contradictions; do not resolve by timestamp. Use checked-base writes/normal fast-forward pushes, never force push or modify the source project checkout. Stop when the safe write capability or required disclosure approval is missing.

Verify the resulting remote commit and update local checkpoints only after confirmation. On a rejected/ambiguous upload, inspect remote state before a bounded retry. Report the commit link and exact scope, exclusions and unresolved conflicts. No automatic background push follows.

## Load task details before acting

Read the [organization core](references/organization.md) and follow its conditional reference triggers. Before source or topic memory writes read [record rules](references/records.md); before verification or verification-record updates read [freshness rules](references/freshness.md). For reload/publish/fetch also read [operation scope](references/operations.md). Do not load unrelated detail files or reread unchanged instructions still in active context. Never omit needed evidence or scope checks to reduce context.
