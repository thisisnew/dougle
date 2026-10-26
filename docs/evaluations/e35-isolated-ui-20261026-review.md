# E35 isolated receipt UI review — 2026-10-26

## Scoped result, not E35 acceptance

`e35-isolated-ui-20261026.json` records `SCOPED_CHECKS_COMPLETED_PENDING_ACCEPTANCE_REVIEW`. The acceptance inventory is unchanged. Parent manually reviewed both checkpoints; direct internal dispatch and local-draft coverage remain for Fermat's audit. This run does not establish complete E35 acceptance.

- Installed Intel 0.1.22 backend: `/Applications/Knowledge Forge.app/Contents/Resources/resources/knowledge-forge.jar`.
- JAR SHA-256 before/after: `e0e43e1084177ea4d28530547cc762bce339d2de2f5721d21ce70d0ef4eeb58d`.
- Runtime: 2026-10-26 12:10:38–12:21:28 UTC; log `/tmp/dougle-e35-isolated-ui.log`.
- Evidence JSON SHA-256: `410484d2059ea4a8b4cc60b84a03d32342200283df209c9f542af5d8490df109`.
- Harness SHA-256: `add1b67cbb9fff0e02daafde76f86efdad7b2ef1543557eaf558982dec3d72af`.
- Imported unchanged read-only helper SHA-256: `6e8c0dbbadc0ad2757f6451f2a0ed93d71cb54938e34385539c18b31b9547747`.

## Observed checks

Five offline harness guard tests passed before runtime; these are not acceptance verdicts. The runtime used its own temporary HOME/DB, two synthetic workspaces and three seeded historical receipt states: PREVIEW, SENT and UNCERTAIN. SENT was inserted as synthetic history, not produced by posting to Slack.

The actual bundled React UI was served through a restricted loopback bridge and reviewed by parent using Ego, not native WebKit. Initial and restarted checkpoints each recorded manual inspection of all three exact receipt bodies/statuses, explicit detail refreshes, absence of outgoing reply controls, switching to the empty workspace without stale receipt content, and returning to preserved receipts. Parent reports TaskSpace 14 finished.

The two UI traces contain 451 and 125 requests. Each has six successful receipt GETs (list, all three detail refreshes, reopened list and repeated UNCERTAIN detail). No reply mutation request, forbidden-route attempt or unresolved trace entry occurred. Each phase also contains two successful local workspace-selection PUTs and one automatic AI connection-check POST intercepted with 409 by the bridge; that connection check was never forwarded or executed.

The backend independently rejected 240 direct API requests with 403 and `READ_ONLY:` across preview, execute and acknowledge-uncertain routes. Inputs included nominal, malformed, absent and explicitly approved bodies, existing receipt IDs and a missing workspace. These requests bypassed the bridge, so the observed denials came from the packaged backend, not only the harness allowlist. This does not cover every possible route or an in-process direct dispatch invocation.

After each UI phase, receipt rows and inbox rows remained exactly equal to their stopped-DB baselines. Answer-job, generation-job and investigation-plan tables remained empty. There were three isolated backend starts: initial database creation, seeded initial UI phase, and restarted UI phase. The report's `restarts: 2` counts the latter two starts.

## Safety and cleanup

macOS sandbox policy denied non-loopback networking; JVM HTTP(S) used a rejecting proxy; the UI bridge used a narrow route allowlist and self-only CSP. The proxy observed zero requests. This count is not a measurement of all blocked transport attempts. No real Slack account, credentials, external AI or user database was used. No model/inference command was available to the isolated backend.

After completion, runner PID 97018 and owned backend PIDs 97025, 97240 and 1581 were absent; bridge ports 65190 and 65464 had no listening process. The owned `dougle-e35-ui-90mEWR` temporary directory was removed. User app PIDs 93344/93351 remained running and were not stopped or mutated by this harness. Installed JAR and shared smoke helper hashes remained unchanged.

## Manual evidence limitations

Parent ACKs are checkpoint/hash/phase-bound and retained in the JSON with separate request traces. Screenshots `docs/screenshots/e35-initial.png` and `docs/screenshots/e35-restart.png` both have SHA-256 `0650657675f971840dfb2cfc69390b98ffff9a2d0fb92d06c0285c598208f119`: they are byte-identical and must not be presented as independent visual proof of restart. Restart evidence rests on the distinct process lifecycle, checkpoint-bound manual ACK, fresh trace and persisted-row checks. Parent reported visually reviewing the screenshots; this review does not independently reclassify the images.

Uncovered: local draft creation/copy behavior, direct in-process dispatch audit, real Slack infrastructure, native WebKit presentation and whole-product external-write enforcement. Synthetic history plus passing UI/API checks does not itself promote E35.
