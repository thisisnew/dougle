# E37 bounded artifact plan — NOT RUN

## Current result: .25 scoped runtime completed

The historical NOT_RUN plan and .24 failure below are superseded only for the executed headless scope. One approved .25 attempt on `5cf78b6441a99f893ecbaf6e81babd3e17e01e46b154f3c772e43abe138ca22a`, using finalized Intel bundle Java/models (not installed assets), completed 88 owner/foreign read pairs and eight search/injection groups. The shared strict sandbox's approved root-literal read rule was retained without additional permissions. Cleanup and unchanged artifact hashes were verified; lease released. See `e37-isolation-025-5cf78b64-20261026-review.md`. Memory-provider/proposal/UI gaps remain; no full E37 promotion.

## Latest: one b5 attempt stopped at JVM startup

The `967cd7…` pin below is superseded and rejected by current guards because its frontend assets were stale. Parent authorized exactly one attempt on corrected .24 SHA-256 `b5b59534ed02f1ad342a64d55f6e40cff03947997af2c07340b5b9bd85473d47`. Eleven offline guard tests passed. That attempt ended before READY with SIGABRT, zero stdout/stderr bytes and zero completed checks. See `e37-isolation-024-b5b59534-20261026.json` and its review. No retry, sandbox broadening or E37 promotion occurred. The planned checks below remain unexecuted.

## Executable runner prepared; runtime lease still pending

`scripts/expert-e37-runtime.mjs` now implements the headless bounded runtime. Ten offline Node tests pass across runtime and plan guards; no .24 runtime has been started. Its only approved artifact SHA-256 is `967cd7e710a8a39c81bd6d13906677f89cc80799424370d659b91feef723d188`.

Invocation after parent coordination only:

```sh
node scripts/expert-e37-runtime.mjs --run-approved-isolated \
  --version 0.1.24 --source-jar /ABSOLUTE/approved/source.jar \
  --sha256 967cd7e710a8a39c81bd6d13906677f89cc80799424370d659b91feef723d188 \
  --java /ABSOLUTE/approved/runtime/bin/java \
  --models /ABSOLUTE/approved/local/models \
  --report /ABSOLUTE/new/e37-report.json
```

The explicitly named source may be read from `backend/target`; it is never launched there or modified. The runner verifies its pin, exclusively copies it into its own private temporary directory, makes the snapshot read-only, verifies both copies, and launches only that snapshot. Startup rechecks the snapshot hash. Java/model paths have no installed-app default. Four local search model assets are hash-verified using the existing model manifest; no download or external AI is permitted. Report files are exclusive-create, not overwritten.

Two real local API uploads, indexed positive hits and attachment-bound thread/bundle creation precede stopped-DB insertion of terminal synthetic answer/SEARCH-plan/browser records for each workspace. No job-start API is called. The browser fixture has a synthetic never-accessed HTTPS scope and populated snapshot text. Each terminal owner GET must return its canary; wrong-workspace GETs must return 404 without it. The runner covers 88 owner/foreign read pairs (11 routes × two owners × two active selections × two phases), eight positive-search/foreign-thread-and-attachment-injection groups, unchanged persisted rows and restart. Local search and thread creation do not generate an AI answer.

The overall runtime interrupt is 12 minutes; startup 90 seconds; each model/index readiness wait 120 seconds; HTTP 10 seconds (terminal SSE 5 seconds); responses 512 KB; stopped SQLite calls 5 seconds; owned process termination escalates after 15 seconds and fails at 20 seconds. The report preserves partial checks on failure and cleanup/hash evidence. There is **no manual UI checkpoint dependency**. All UI switch presentation checks remain NOT_RUN; active-workspace selection is exercised via the local API. Populated in-memory MySQL/Datadog/operational-capture checks remain deferred, so success is explicitly `SCOPED_CHECKS_COMPLETED_NOT_FULL_E37`, never full E37 acceptance. Earlier UI/.23 proposals below are historical.

## Superseding freeze: .24 pending

User priority now removes manual OperationalTools UI; Volta owns that frontend change. Runtime is paused until parent supplies the new frozen **0.1.24** artifact and lease. The pure options contract is `--version 0.1.24 --jar ABSOLUTE_FROZEN_JAR --sha256 EXACT_HASH`, with no installed-app/build-target fallback; any `target` path segment is rejected. Actual file hashing, realpath/symlink containment and artifact version verification remain requirements for the future runner, not operations performed by these pure guards. No .24 hash has been supplied or assumed.

Current guard tests no longer require opening or inspecting manual operational-tool panels. The future UI checkpoint is history A→B→A only; backend tool/browser ownership probes remain independent of removed UI. Six offline guard tests pass. The .23 plan below is preserved as the earlier proposal, not current runtime authorization; its tool-panel UI requirement and .23 pin are superseded by this section.

Prepared against current production contracts; the exact frozen Intel 0.1.23 JAR path/hash and runtime lease must be supplied by parent before execution. No runtime, build, Maven, credentials or AI dispatch was initiated. `scripts/expert-e37-isolation.mjs` currently contains only pure plan/guard functions, not an executable runtime runner. Its five Node guard tests pass; this is not acceptance evidence.

## Ownership versus active selection

The active workspace is a UI selection, not authorization to replace the workspace explicitly named in an API URL. A legitimate owner read must still succeed when the other workspace is selected. Conversely, selecting the owner cannot make an owner object readable under another workspace's URL. Test both directions, not just an empty-workspace 404.

- `conversation/application/ChatThreadService.java`: `all(workspaceId)` loads workspace-scoped bundles; `find/get` filters the thread within that set and scopes jobs to the same workspace. Creation validates parent/thread and attachment context within the explicit workspace.
- `search/adapter/LuceneSearchIndex.java`: both lexical and semantic queries filter the indexed `workspace` term. Exercise actual indexed documents and inspect result IDs/text, not merely an empty result.
- `attachment/application/AttachmentService.java`: upload checks workspace existence; reads/binding use workspace plus attachment ID. There is no attachment GET controller: test foreign attachment injection through local thread creation, then prove the original attachment remains usable by its owner. Do not misclassify an unmapped URL's 404 as isolation proof.
- `investigation/adapter/JdbcBrowserInvestigationStore.java`: job lookup uses workspace plus request ID; list uses workspace plus bundle ID. Browser service list also validates bundle ownership. Seed only valid terminal synthetic jobs while the isolated backend is stopped; never queue a browser execution.
- `investigation/adapter/MysqlInvestigationService.java`: results are held in a pending map and checked against `preview.workspaceId`; connection configuration/epoch validation can run before ownership lookup. An unconfigured-provider error is not proof that populated results are isolated. MySQL connection settings are global; result ownership remains workspace-bound.
- `investigation/adapter/DatadogInvestigationService.java`: pending result ownership is workspace-bound, plus workspace connection-revision validation. `OperationalCaptureService` similarly binds pending captures and revalidates saved attachment ownership/hash.

All Java paths above are under `backend/src/main/java/dev/knowledgeforge/`. Source inspection is design evidence, not a claim that a packaged artifact was executed.

## Proposed bounded runtime

1. Pin parent-approved .23 artifact, rejecting `.22` and `backend/target` paths. Validate actual SHA-256 before and after. Own synthetic HOME/DB only; loopback-only sandbox and rejecting proxy; missing model-provider executables and no profile access. Bundled local search assets may be read, with no downloads. Suggested limits: startup 90 seconds, search readiness/index drain 120 seconds, individual API 10 seconds, SSE first bounded observation 5 seconds, each parent UI checkpoint 15 minutes, overall 35 minutes.
2. Create A/B with distinctive canary text through local upload APIs. Wait for READY and indexing, then create actual local evidence/thread records without any answer approval/job dispatch. For the same query, prove each workspace returns its own positive hit and neither the other workspace's ID nor canary. Repeat with the active workspace reversed. A zero-hit owner control is a failure/incomplete check, never a pass.
3. Use a stopped-DB fixture only for terminal synthetic answer/plan/browser records that cannot be created without executing external tools. Validate their exact positive owner responses first. Label these as seeded-state boundary tests, not completed AI/browser operations. Preserve and compare rows before/after. If artifact schema prevents a valid positive control, block that check rather than substitute nonexistent IDs.
4. The pure matrix defines 11 owner-positive/foreign-negative GET pairs: entry metadata/content/preview, thread, bundle/validation, answer job/events, browser job/list and plan job. Run each with both active workspace selections, before/after one restart. Add thread/parent injection and foreign attachment creation attempts through local APIs; ensure denial leaves owner records and job counts unchanged. SSE must reject the foreign job before streaming and never expose its canary.
5. Parent Ego checkpoint through bundled UI: inspect A's history, switch B and inspect absence of A canaries/stale tool/browser panels, then return A and verify preserved content. Bind manual ACK to exact hash, workspaces, checkpoint and restart phase. Intercept the app's automatic AI connection-check POST rather than allowing it. No question submission from the UI and no operational execution buttons.
6. Preserve request/status matrix, exact fixture identities/digests, manual ACKs, independently captured screenshots, sandbox/proxy observations, artifact hashes and owned-process/temporary-data cleanup. No central E37 verdict change without parent review.

## Explicit partial-coverage blockers

No credentials or production-code injection means there is currently no honest way to populate MySQL/Datadog pending results or operational-capture previews in this immutable artifact. Those checks remain BLOCKED; random ID 404 or disconnected-provider errors cannot substitute. Durable captured attachments can test attachment isolation, but cannot establish the live provider result boundary. A seeded terminal tool-proposal record may cover persisted proposal disclosure only after its owner-positive response is validated, not tool execution. Local search is testable without external AI but still requires bundled model readiness and real index hits.

Full E37 remains unclaimed. Request parent ownership/approval before adding any product test seam or extending the runtime beyond this isolated scope. E35's separate dispatch audit and missing dedicated local-draft implementation do not change E37 or promote E35.
