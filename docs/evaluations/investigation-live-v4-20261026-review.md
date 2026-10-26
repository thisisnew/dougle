# V4 artifact — E01–E03 and manual-proposal routing

Artifact SHA-256: `675a7c59e13edc8ee849b23977699e600331555d8f53c669ef8e7f10406e3489`. Reviewer: Codex evaluation agent, manual semantic comparison against recorded source quotes, **not independent human review**. Requested CODEX/default (empty requestedModel); actual model ID, tokens and currency cost remain unobserved. Native metadata 0.1.21 did not change this JAR.

## Batch11: three variants per case

[Raw evidence](investigation-live-batch11-v4-e01-e03-20261026.json) includes exact questions, complete answers, source quotes, plans, request IDs, bundle/thread IDs, hashes and timestamps. **36 dispatches: 18 plans + 18 answers**, all successful, no automatic retries. Nine separate two-turn threads, three wording variants for each case. Manual review supports **artifact-scoped PASS for E01, E02 and E03**, not a transfer of old-artifact results.

| Case/variant | Actual-versus-expected manual review |
|---|---|
| E01/0 | Both answers cite Strategy.java R1; interface/FixedStrategy ROCK/Player delegation are correct. Implicit followup focuses on delegation, not State/Singleton/business distractors. |
| E01/1 | Components and suggested inspection order match R1; followup stays with Strategy despite receipt/NightState excerpts. |
| E01/2 | Player constructor, private final field, play delegation and ROCK behavior all follow R1; “that code” resolves correctly. |
| E02/0 | Initial caller is SyntheticReceiptEndpoint.handle (R2). Followup cites ReceiptFlow.java R3 for service.complete → repository.markReceived → UPDATE receipt_header status RECEIVED with receiptId binding; explicitly disclaims actual database execution. |
| E02/1 | Initial caller cites R1. Followup recovers missing ReceiptFlow.java with SEARCH, then cites captured R3 for the complete path/table/state; correctly excludes accepted_qty from this implementation. |
| E02/2 | Caller cites ReceiptFlow.java R3. Followup cites R2 for actual code path and distinguishes receipt.md R1's separate business description; no fabricated persisted result. |
| E03/0 | Correct Strategy setup, then receipt-only completion explanation: all items inspected and every inspected quantity equals expected. Additional storage details are supported by receipt.md R1. |
| E03/1 | Correct Player/FixedStrategy roles, then explicit switch to both receipt completion conditions and documented effects. No old-topic mixing. |
| E03/2 | Correct delegation setup, then concise receipt answer with both conditions from R1. No Strategy/Player/ROCK carryover. |

All 18 answers are grounded under these limited criteria. There were **17 STOP plans and one justified SEARCH**, and **zero operational proposals** for ordinary code/domain questions. E02 variant1/turn1 preserves expected STOP versus actual SEARCH mismatch: the original evidence lacked ReceiptFlow.java, containing a different ReceiptService plus distractors. Query `ReceiptFlowService complete SyntheticReceiptEndpoint handle 저장 repository mapper UPDATE status 테이블` recovered it; immutable capture and fresh answer approval preceded the answer. Thus E02 grounding passes, but the exact-decision fixture is not uniformly matched. No unnecessary additional local search identified in this batch.

| Case | Unique dispatches | Combined turn p50 / nearest-rank p95 | Turn latency sum |
|---|---|---|---|
| E01 | 12 (6 plan + 6 answer) | 10828.5 / 24768 ms | 76932 ms |
| E02 | 12 (6 plan + 6 answer) | 11073.5 / 13298 ms | 66594 ms |
| E03 | 12 (6 plan + 6 answer) | 9908.5 / 11429 ms | 59974 ms |

Each latency distribution has only six samples and includes retrieval/planning/answer work. No human intervention inside the batch; separate explicit batch authorization is not an efficiency benchmark. Same-ID job reconciliation returned existing terminal jobs, not additional observed generations. No comparative human baseline or controlled warm/cold measurement.

## Batch12: six plan-only proposals

[Raw proposal evidence](investigation-live-batch12-v4-proposals-20261026.json). **6 plan dispatches, zero answer dispatches**, all successful. Three synthetic current-record questions (SYN-1001/1002/1003) returned PROPOSE_MYSQL_READ; three explicitly bounded synthetic incident-log questions (SYN-2001/2002/2003) returned PROPOSE_DATADOG_LOGS. These are six distinct questions, three per tool; the harness's generic variants field is not a count of repetitions for this dedicated proposal list.

Manual comparison: current-row requests explicitly distinguish present database records from code explanations, making MySQL proposals appropriate. Log requests explicitly name Datadog, synthetic environment/service and five-minute intervals, making log proposals appropriate. All six outputs have query/output/toolStatus null, truncated=false; exact proposal DTOs contain only binding IDs, kind, required field names and executionAllowed=false. No SQL, connection, table, environment, index or time-range values were invented in proposal output, even where the question supplied partial scope. No final answers were requested or produced, and no claim of real records/incidents is made.

The new plan-only harness records its HTTP method/path trace (no auth/header/body dumps), rejects non-allowlisted POST routes, and verifies empty answer/generation job lists plus NONE answer approval after every proposal. Recorded POSTs are limited to authenticated local setup, synthetic source ingestion, chat bundle creation, plan preview and plan job creation. No operational execute route or answer-generation route was called. This is measured harness-request evidence and persisted-job verification, **not a universal audit of every internal backend invocation** or actual external DB/log validation.

These six routing observations are supplemental evidence only: **no new E04–E50 PASS**. E06/E11 remain partial evidence; do not double-count reused trials. The existing six-proposal fixture tests and other live harness tests passed (10 total); those tests do not count as real model calls.

## Release and limitations

Total authorized phase: **42/42 dispatches, 24 plans + 18 answers** across two fresh isolated application databases. All owned process groups/tempdata cleaned before release; no Java runtime remained and the post-run JAR hash matched. No credentials read, user/company DB used, Maven/package/JAR write, or additional AI retry/call occurred. Runtime lease released before this documentation review so packaging can proceed.

Success phase COMPLETED is derived from job status. Failure-only typed stages/counters were not exercised; previous timeout causes are not proven fixed. Short synthetic conversations do not establish restart/long-history behavior, general accuracy, all injection modalities, operational service execution, full E01–E50 acceptance or any different artifact's correctness.
