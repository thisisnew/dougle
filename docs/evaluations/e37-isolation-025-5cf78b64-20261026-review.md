# E37 .25 isolated runtime — scoped checks completed

One authorized attempt, 2026-10-26 13:58:24–13:59:50 UTC (85.645 seconds), terminal session 8876, runner exit 0. Log: `/tmp/dougle-e37-025-5cf78b64.log`. No automatic retry or additional attempt. Earlier .24 startup-failure evidence remains unchanged.

## Artifact and boundaries

- JAR SHA-256: `5cf78b6441a99f893ecbaf6e81babd3e17e01e46b154f3c772e43abe138ca22a`.
- Source JAR, Java and four search assets came from the finalized Intel bundle at `desktop/src-tauri/target/release/bundle/macos/Knowledge Forge.app/Contents/Resources/resources`, not the installed app. The JAR was copied into an owned, read-only, hash-verified temporary snapshot before execution.
- Java SHA-256: `e5df547d287f27e7e8d4d78e8133c0ba9833c512f60de67bffa46c609a93ac8f`; all four model hashes are recorded in the JSON.
- Existing shared E50 strict file/network policy, including only its approved `(allow file-read-data (literal "/"))` addition, was used without further permissions or fallback. Loopback-only networking and rejecting HTTP(S) proxy remained enabled.
- Eleven offline harness guards passed before runtime. Those tests are separate from the runtime counts below.

## Actual runtime checks

The report result is `SCOPED_CHECKS_COMPLETED_NOT_FULL_E37`, mode `simulated`. Two workspaces received actual local API uploads with distinct canaries, real local search hits, attachments and attachment-bound threads/bundles. Search model READY was required. No answer generation, planner execution, browser navigation or external provider execution was requested.

Stopped-DB insertion created populated terminal synthetic answer, SEARCH-plan and browser records for each workspace. These seeded records test persistence/disclosure boundaries, not real AI output, live tool results or browser capture. Owner responses for thread, answer/SSE, browser and plan records were required to contain the correct canary.

- **88 successful owner/foreign read pairs:** 11 routes × two owners × two active workspace selections × two runtime phases separated by restart. Owner controls returned 200; foreign-workspace substitutions returned 404 without the owner's canary. Routes: entry metadata/content/preview, thread, bundle/validation, answer job/events, browser job/list and plan job.
- **8 successful search/injection groups:** owner search returned the correct entry and text, excluding foreign canaries/IDs; foreign thread/parent and attachment injection attempts each returned 404; owner thread remained unchanged. These are eight groups containing sixteen denial requests, not eight additional full criteria.
- Full persisted-row comparisons after both phases confirmed evidence bundles, attachments, answer jobs, generation jobs, investigation plans and browser records unchanged from the seeded baseline. Active-workspace selection changes were intentional local settings writes, not a claim that the entire DB was byte-identical.
- Zero actual AI dispatches; zero observed requests at the rejecting proxy. Proxy counts alone do not measure every blocked transport attempt. No credentials, user database or external business systems were used.

## Cleanup and evidence

Runner PID 30266 and backend child PIDs **30282, 30324, 30413** were independently checked absent. The owned `dougle-e37-runtime-c1zi8F` directory and copied JAR were removed. Source/snapshot-before-removal and Java hashes were unchanged. No bundle/JAR writes, Maven, install or user-app mutation occurred. Runtime/asset lease released after cleanup verification.

- Report: `e37-isolation-025-5cf78b64-20261026.json`.
- Report SHA-256: `e4ddf2a9adff3684bef021b660beb01d472a81287b483c45c1f5a8d5a1f5eaed`.
- Executed harness SHA-256: `70f84353f3ffb7907cb185245d4715da8d3a30b1b094645259978a8fcdcbf980`.

The report's single `startup` object describes the last child: READY followed by exit 143 after intentional SIGTERM cleanup. It is not a full per-start transcript; `backendPids`, both phase matrices and final comparisons establish the multi-start run.

## Not full acceptance

Populated MySQL/Datadog results and operational-capture pending state remain deferred because they are process-memory provider state. Persisted operational tool-proposal payload isolation is NOT_RUN; the seeded plan is SEARCH, not an operational proposal. No UI switching or native WebKit check was performed. No real provider/account test or semantic answer review occurred. **Do not promote E37 from this scoped synthetic result.** Parent acceptance review remains separate.
