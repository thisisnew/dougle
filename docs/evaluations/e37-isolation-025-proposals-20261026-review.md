# E37 .25 proposal extension — incomplete single attempt

One authorized run, 2026-10-26 14:10:22–14:11:07 UTC, session 92834, runner exit 1. No retry. Artifact SHA-256 `5cf78b6441a99f893ecbaf6e81babd3e17e01e46b154f3c772e43abe138ca22a`; finalized Intel bundle Java/models, not installed assets. Existing strict sandbox/root-literal rule unchanged. Log `/tmp/dougle-e37-025-proposals.log`.

Separate terminal SUCCEEDED PROPOSE_MYSQL_READ and PROPOSE_DATADOG_LOGS records were seeded per workspace, distinct from SEARCH. They are not model-generated or executed operations. Twelve offline guards passed before the run.

## Observed failure and evidence limit

Eleven original owner/foreign read pairs completed. Execution then reached the first MYSQL proposal: owner status 200 and exact workspace/bundle/job/kind/requiredFields/`executionAllowed:false` checks completed before the failing assertion. Foreign status 404 was also checked before the failure. However, no proposal observation was appended because the subsequent substring guard failed with `Foreign content leaked in denial response`. The report therefore contains **zero completed proposal pairs**, and no restart phase was completed. These ordering facts follow the executed harness, not a separately retained response body.

The guard incorrectly treated the caller-supplied job ID as confidential even in an error's reflected request URL. Production returns Spring ProblemDetail; exact request-path reflection is a plausible explanation. **The failed response body was not retained, so this run cannot prove which substring matched, confirm path reflection as the actual cause, or establish a product content leak.** Preserve this as an incomplete attempt, not a pass or a confirmed product defect.

Parent-requested offline audit: the failed guard checked three substrings in order—owner canary, owner bundle ID, supplied proposal job ID—and emitted the same message for each. None of the matched marker/field/body was retained. Therefore the exact failed field is **unknown**, not retrospectively established as `instance`. The offline synthetic ProblemDetail fixture demonstrates that an exact requested `instance` containing the supplied job ID reproduces the overbroad condition; this is guard proof, not reconstruction of the actual response.

Expanded offline proof accepts only the exact requested `instance`, requires HTTP/body status 404 and standard ProblemDetail fields, rejects all arbitrary extension fields (including nested payloads and even unknown benign extensions), rejects reflected IDs or private markers in `detail`/`title`/`type`, rejects non-string text fields and mismatched instance suffixes. Safe future diagnostics classify matched marker names by standard field or generic extension category without storing marker values. Thirteen tests still pass after expansion; no further runtime has occurred. Failed JSON hash remains unchanged.

After stopping, an offline-only correction permits `instance` solely when it equals the exact requested path, then rejects owner canary/workspace/bundle/job identifiers everywhere else in the error body. New tests cover reflected paths, mismatched instances, non-404 status and leaked detail fields. Thirteen guards now pass. The corrected runner also records bounded problem keys, path-match boolean and response hash before validation. **It has not been rerun; new runtime authorization is required.**

## Cleanup and immutable evidence

Owned backend PIDs **32441, 32470** were independently checked absent. Temporary directory `dougle-e37-runtime-IzKtVJ` and copied JAR were removed. Source/snapshot-before-removal and Java hashes remained unchanged. Zero AI dispatches and observed proxy requests; no provider execution, user data, JAR writes or sandbox relaxation. Lease released.

- New report SHA-256: `1a3a88082c1cd329956622685d7d2177156be0c92f59d513a7ed44c06d479bca`.
- Executed pre-correction harness SHA-256: `456bb74188019241170975e7275823c784e6357986ec21b2fbdaf99ffcbabdd6`.
- First successful .25 report remains byte-identical: `e4ddf2a9adff3684bef021b660beb01d472a81287b483c45c1f5a8d5a1f5eaed`.

No E37 promotion. Populated memory-provider isolation and UI remain outside scope; persisted proposal isolation is still incomplete.
