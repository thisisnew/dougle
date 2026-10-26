# Synthetic live investigation evaluation

The latest user authorization supersedes the historical 30-call total limit. Runs remain bounded per batch; there is no unattended infinite loop or automatic retry after failure. Only generated synthetic documents enter a temporary Workspace/database. The existing dedicated app Codex profile is passed to the provider CLI; the harness never reads or copies credentials. No Maven/package build is performed while the production JAR is in use.

`scripts/expert-live-eval.mjs` supports `FORGE_EXPERT_MODE=investigation`. The matrix is in `scripts/expert-live-investigation-fixtures.mjs`: implicit followup with wrong-topic distractors, explicit topic switching, latest target plus environment correction, absent facts, conflicting drafts, injected instructions, and sufficient versus missing evidence. Expectations and evaluator criteria are outside the imported source directory.

Example bounded run (use a new report filename every time):

```sh
FORGE_EXPERT_LIVE=approved \
FORGE_EXPERT_MODE=investigation \
FORGE_EXPERT_SCENARIOS=no-evidence-recovery,explicit-topic-switch \
FORGE_EXPERT_MAX_CALLS=8 \
FORGE_EXPERT_PROFILE="$HOME/Library/Application Support/dev.knowledgeforge.desktop/codex-profile" \
FORGE_EXPERT_JAR_SHA256=7fe16e3f7637a5b536702d12059d462117b9d46037e0f6c04813a8b2b8fd680e \
FORGE_EXPERT_REPORT="$PWD/docs/evaluations/NEW-report.json" \
node scripts/expert-live-eval.mjs
```

Each turn creates an immutable chat bundle, prepares and explicitly approves a PlanModel request, and waits for STOP or SEARCH. SEARCH executes one registered local search, captures new evidence with the same thread and original parent binding, verifies no preexisting answer approval, then obtains a fresh answer preview/approval and executes the answer. STOP answers from the original evidence under its own approval. Replays use identical request IDs and must return identical completed jobs/captures. Thread bundle counts include captured turns.

Dispatch attempts are persisted **before** each POST, including uncertain acceptance. Plan and answer counts are distinct; replay HTTP requests are not counted as new generation dispatches. These are application dispatch counts, not independently observed provider billing/call totals. Requested default model is an empty string; actual provider model ID is unobserved and recorded as null. Tokens and monetary cost are not collected.

Automated regex checks are only screening, never a correctness verdict. Review the full answer against its quoted evidence and the synthetic ground truth. Record answer correctness separately from expected/actual planner decisions: searching despite sufficient evidence can be wasteful without producing a factual error, and stopping when the corpus demonstrably lacks a fact can be reasonable. Conflicting drafts must not be resolved by invented dates or authority. A failed/blocked execution is not a correct answer.

Each report is created exclusively, records the JAR hash, and is updated after each result. The backend uses a random loopback port, isolated data directory and session token. Normal completion, exceptions and handled signals close the backend and remove temporary data; newer harness runs also kill the owned process group to remove surviving descendants. No unrelated process is killed. SIGKILL of the harness itself cannot guarantee cleanup.

Initial-JAR limitation: artifact `6b60123…` rejected planning bundles without evidence. Harness fixture v2 targets the replacement `7fe16e3…` with V3 planning and exact maintenance-lock compatibility. It passes empty bundles to the planner too. `no-evidence-recovery` first creates a question in an empty isolated Workspace, asserts immutable `NO_EVIDENCE` with no approval, then imports synthetic sources. The real planner must SEARCH, capture Strategy.java, and leave the original empty bundle unchanged before fresh answer approval. This is deterministic evidence-arrival recovery, not proof that semantic query rewriting fixes arbitrary retrieval misses.
