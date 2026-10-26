# Credential-content blocking boundary

This is a focused E38 mitigation, not an E38 acceptance pass or a general secret/PII detector. No credential files are read. Tests use invented strings and mock models, not external AI or business systems.

## Policy

`security/application/CredentialContentGuard` rejects recognized credential-like content with a generic Korean 409 error that does not echo the match. It never redacts or rewrites evidence, prompts, inbox messages, archive bytes, or old approvals. Datadog's existing display redactor informed the assignment/auth/JWT/private-key patterns; its broad email/URL masking is deliberately **not** used as a blocking rule.

Recognized patterns:

- PEM private-key BEGIN markers; JWT-shaped three-part strings starting `eyJ`.
- Slack `xox[baprs]-` / `xapp-` prefixes with at least eight following token characters; GitHub token prefixes with at least twenty; `sk-` prefixes with at least twenty.
- AWS `AKIA`/`ASIA` followed by sixteen uppercase alphanumeric characters.
- Bearer/Basic auth values containing at least sixteen token characters; HTTP(S) user/password URL userinfo, not ordinary URLs.
- Named credential assignments (`password`, `passwd`, `secret`, `token`, API/application keys, access/refresh tokens, authorization/cookie headers): values at least sixteen characters containing both a letter and a digit. Common redaction markers, example/placeholder/changeme/your-prefixed values, `${...}`, `<...>`, `process.env.` and `System.getenv(` references are excluded from this assignment heuristic.

Short code such as `token=id` and `password=example`, ordinary emails and URLs remain allowed. Long mixed letter/digit values and token-shaped examples may still be false positives. Conversely short passwords, unrecognized providers, arbitrary opaque credentials, encodings/obfuscation and secrets in images or unextracted binary content can escape these rules. Passing the guard is **not** a claim that data is safe or contains no PII. Matching literals in source code must be excluded from newly selected material rather than silently rewritten in an approved prompt.

## Enforced boundaries

- Slack inbox adoption checks both the stored event text and proposed question before creating a new local conversation. Receiving/reading the inbox remains local and unchanged. An already-completed adoption's idempotent lookup still returns its existing result without creating a new turn or invoking AI. This adoption operation is not Slack posting.
- Answer previews inspect decoded immutable payload string values, including titles, quotes, supports and saved chat history. Selected conversation history is checked before `ContextPrompt` serialization; legacy frozen context prefixes are checked too. Plan previews also check question-only/no-evidence payloads. Model job start and immediately-before-model execution already call these same preview paths; old approval digests cannot bypass the guard. No digest is silently recomputed over sanitized content.
- Workspace archive export and import-preview check typed catalog string values, bounded document bytes decoded as UTF-8, and analyzer-extracted text. Import-apply rechecks the retained catalog/document bytes and prepared extracted text before restoring. Successful old import receipt replay still returns the recorded result; it does not recreate or alter data. The low-level ZIP integrity codec remains content-agnostic.

Existing sensitive local material is not deleted, migrated or rewritten. No new promise is made about inbox display, stored local logs, historic records, image/OCR scanning, whole-product AI entry points outside answer/planning, encrypted documents or all personal information. Full E38 remains incomplete until separately evaluated against its broader acceptance criteria.

## Focused regression command

```sh
bash scripts/maven.sh -Dtest=CredentialContentGuardTest,CredentialPreviewTest,CredentialArchiveTest,SlackInboxServiceTest,WorkspaceArchiveIntegrationTest,AnswerJobIntegrationTest,InvestigationPlanIntegrationTest,ArchitectureContractTest test
```

This runs source tests only; no packaging or external AI calls are needed. Immutable pre-policy `.21` artifacts do not contain this source change and remain blocked from installation/publication.

2026-10-26 focused run: **74 tests, 0 failures, 0 errors, 0 skips**, Maven exit 0; log `/tmp/dougle-credential-boundary-tests.log`. This includes architecture checks, archive export/import, Slack adoption, answer jobs and investigation jobs. Backend JAR SHA-256 remains `675a7c59e13edc8ee849b23977699e600331555d8f53c669ef8e7f10406e3489`; no JAR was rebuilt. This is synthetic regression evidence, not a full E38 pass.
