# E05 variant0 — partial diagnostic, stopped without retry

- Artifact: `5cd81269031769b8c65af4a3cfc136dcd562e30a0c3e91f255f6a959f4a9bd0d`, unchanged after cleanup.
- Raw evidence, preserved unchanged: [full report](e05-variant0-readonly-20261026.json). Console: `/tmp/dougle-e05-variant0-readonly.log`.
- Authorized scope: variant0 only, at most64 dispatches, synthetic local policy sources and32 intended real questions. No remaining variants or automatic retries were authorized.
- Actual: **6 dispatches = 3 plans + 3 answers**. All3 plans succeeded with STOP. Answers for zero-based turns0–1 succeeded and received separate AGENT semantic acknowledgments from designated reviewer Volta. This is not independent-human review. Turn2 answer failed; no completed answer was available to review.

## Exact failure and limits

The third answer job ran from `2026-10-26T10:19:33.306978Z` to `10:21:33.410737Z` and failed at `CODEX_APP_SERVER / WAIT_RESPONSE`, approximately120seconds. Safe diagnostics: `stdoutBytes=24623`, `stderrBytes=0`, `stderrSignal=NONE`, `execStdinWritten=false`. The last flag is an exec-transport counter, not evidence that the app-server prompt was unsent: the session had advanced past the turn/start response. Observed stdout bytes do not establish a complete final answer.

This is an observed transport deadline failure, **not an observed wrong semantic answer**. The third question asked for the meaning of expiration review without inventing dates or actual state. Its plan succeeded with STOP; actual plan/answer context JSON matched and exact provenance checks passed. Context size was **1449/8000 characters**, with **0 omitted questions and0 removed answer characters**. Earlier contexts were316 and995characters. No correction, long-context limit or actual compaction was reached. Source review found no confirmed code defect or prompt-shape rejection; remote generation, event delivery/binding and stream handling cannot be distinguished from the retained counters. Root cause remains unknown. Requested model was provider default; actual model ID and token cost were unobserved.

The harness stopped on the first failed answer, did not resubmit it, and did not execute operational queries or proposals. Owned backend PID77766 exited, temporary trial directory was removed, and cleanup completed at `2026-10-26T10:21:35.019Z`; runtime/JAR lease released. Full answers, current evidence, exact context metadata, jobs, safe diagnostics and the two review acknowledgments remain in the raw report.

**No E05 PASS or inventory promotion.** The first variant is incomplete diagnostic evidence only. The approved full criterion still requires all3 variants with measured compaction plus a separately reviewed loss-awareness negative. No further actual calls are authorized.

## Future harness asset selection (not applied retroactively)

`FORGE_EXPERT_SEARCH_MODELS` may explicitly select an owner-verified immutable model directory. Without it, the runner uses `.tools/search-model/x86_64` for Node `process.arch=x64`, or `.tools/search-model/arm64` for arm64—not the mutable shared desktop staging directory. All4 model/tokenizer files must match architecture-specific pinned sizes and SHA-256 hashes before backend startup and again before each counted external dispatch. Missing/corrupt/wrong-architecture/linked assets fail closed; the harness never downloads, stages, copies or chmods those assets. It records the selected directory, manifest and verification timestamps in future reports.

The directory must remain leased against concurrent mutation; repeated verification is not an operating-system immutability guarantee. This change does not establish a cause for the completed trial's timeout and does not modify its original report or recorded harness hash. Offline tests use synthetic files only; no model/JAR or AI execution accompanies this change.
