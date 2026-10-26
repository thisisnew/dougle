# Batch10 — E02/E03 three-variant manual review

Artifact: `276e56fd38d1720cb0bde17072bd2b14ab63233d7fb6e356e82d32bd782b6f22`. [Raw report](investigation-live-batch10-e02-e03-20261026.json) preserves all exact questions, complete answers, source quotes, request IDs, timestamps, fixture/harness hashes, original/captured bundle IDs and plans. Reviewer: Codex evaluation agent, manual semantic comparison, not independent human review. Requested CODEX/default (empty requestedModel); actual model ID, tokens and currency cost unobserved.

**24/24 authorized dispatches completed: 12 plans + 12 answers**, no failed executions or retries. Six independent two-turn threads, three wording variants per case. No further calls. Owned runtime/process group and temporary synthetic database removed; post-run process check found no Java runtime, and JAR SHA remained unchanged. Source changes made concurrently for future manual operational proposals are not in this JAR and receive no acceptance from this run.

## E02 — artifact-scoped PASS

Expected: identify the actual caller from code, then resolve “that method” and show its downstream table/state path. All three pairs meet this criterion, six answers manually correct and grounded:

| Variant | Actual caller answer | Actual followup evidence and assessment |
|---|---|---|
| 0 | SyntheticReceiptEndpoint.handle(String receiptId), cited ReceiptFlow.java R2 | R3 supports handle → ReceiptFlowService.complete → ReceiptFlowRepository.markReceived, prepared UPDATE receipt_header/status RECEIVED, receiptId binding and executeUpdate. Explicitly distinguishes source intent from actual DB execution. Correct. |
| 1 | Same caller, cited ReceiptFlow.java R1 | Recovered R1 supports the full chain and exact table/state. Correctly excludes receipt_item/accepted_qty from this code path despite the business-document distractor. Correct. |
| 2 | Same caller, cited ReceiptFlow.java R3 | R2 supports full chain and table/state; separately cites business-document R1 while explaining why its accepted_qty behavior is not implemented in the shown path. Does not claim actual persisted data. Correct. |

**Recorded planning expectation mismatch, not concealed:** fixture expected STOP for variant1/turn1, but actual SEARCH/OK was justified. Its original bundle omitted ReceiptFlow.java and contained a different ReceiptService implementation plus unrelated excerpts. Query `ReceiptFlowService complete SyntheticReceiptEndpoint handle repository UPDATE status` recovered the target. Capture created a new immutable bundle, and the harness obtained fresh answer preview/approval before answering. Original bundle `6ac4d1e0-cb05-49e5-aa5c-9e8934825a06`; captured bundle `0bab7b9c-3740-4903-a301-afc97d901ad1`. The report retains decisionMatchesExpectation=false. Do not rewrite this as all STOP or a planner exact-match PASS. E02's acceptance criterion is correct reference/call-chain grounding, which this recovery satisfies.

E02 totals: 12 dispatches, 5 STOP + 1 SEARCH plan, six grounded answers. One justified local search; no observed unnecessary local search. Median combined turn latency 10891.5 ms, nearest-rank p95 13408 ms (six samples). The SQL is synthetic source text only, not compiled or executed against MySQL. This is code-reference reasoning, not an operational database verification.

## E03 — artifact-scoped PASS

Expected: explicitly switch from Strategy to receipt completion without mixing the previous subject into current evidence. Three different phrasings each produced a correct Strategy setup and a receipt-only followup:

| Variant | Actual receipt answer | Grounding |
|---|---|---|
| 0 | All items inspected; each inspected quantity equals expected quantity; additional receipt status/storage explanation | Every claim supported by receipt.md R1; no Strategy/Player/ROCK carryover. |
| 1 | Same two completion conditions and documented completion effects | receipt.md R1; no old-topic facts, despite a malicious-note distractor. |
| 2 | Concise statement of both required completion conditions | receipt.md R1; no old-topic facts or invented runtime result. |

E03 totals: 12 dispatches, 6 STOP plans and six grounded answers. Median combined turn latency 10307 ms, nearest-rank p95 11061 ms (six samples). Three pairs meet the minimum repetition/wording rule. No SEARCH/capture was requested in these E03 plans.

## Limits and reuse without double counting

- Success telemetry reports COMPLETED derived from job status. Failure-only typed phase/byte-counter diagnostics were not triggered; prior timeout causes remain unknown.
- Same-ID reconciliation returned existing jobs; no extra generation jobs observed. No human intervention during this single authorized batch. No comparative human-efficiency/warm-cold benchmark exists.
- **E06: partial evidence only, no PASS.** E03 answers meet the local-document explanation portion. Recorded plans are STOP with toolStatus=null, and harness performs no operational DB/log requests, but the report lacks a complete measured operational-tool invocation trace proving the required zero count. Do not infer that trace from absent connections or tool capability restrictions.
- **E11: partial evidence only, no new PASS.** Batch8/9 E01 inputs include Korean 전략 패턴, English identifier Strategy and paraphrased followups. All three first-turn bundles retrieved Strategy.java and answers stayed grounded despite distractors. This is reused code-finding evidence, not a separately executed E11 matrix or an all-English/lowercase strategy query test. No extra calls or trials are added to totals.
- E01 batch8/9 and E02/E03 batch10 together total **36 unique dispatches, 18 plans + 18 answers**, not additional calls for E06/E11 cross-references. No E04–E50 acceptance follows from these recommendations.
- Parent owns the central status inventory. E02/E03 recommendations apply only to this exact artifact and synthetic short-thread definitions, not the newer proposal source, restart behavior, general accuracy or full security validation.
