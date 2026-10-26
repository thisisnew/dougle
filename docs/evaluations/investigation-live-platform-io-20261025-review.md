# Platform-I/O artifact: real-model followup

Artifact SHA-256: `1ddab26fc24c41a1a25ab8cc351d98659cccefdace8a0b697f54048a20f8afe0`. Synthetic sources and isolated temporary databases only. Reviewer: Codex evaluation agent, manual semantic comparison; not independent human review. Requested provider/model: CODEX/default. Actual model ID unobserved. No raw stderr, authentication data or protocol-message dumps collected. No Maven or package builds during the JAR lease.

## Batch 6 — E01 three-variant attempt

[Raw report](investigation-live-batch6-e01-20261025.json). Planned 3 variants × 2 turns, maximum 12 calls. Stopped at the first execution failure after **6 dispatch attempts: 3 plan + 3 answer**. Plans all SUCCEEDED/STOP; answers 2 SUCCEEDED, 1 FAILED. No retries. Each trial starts a distinct thread; the second thread's first answer failed, so variant 1 followup and all of variant 2 were not executed.

| Variant / turn | Expected → actual plan | Manual comparison |
|---|---|---|
| 0 / 0 | STOP → STOP | Correct. Strategy.nextMove interface, FixedStrategy returning ROCK and Player delegation all match R1. The illustrative `new Player(new FixedStrategy()).play()` result follows directly from that code. |
| 0 / 1 | STOP → STOP | Correct. Emphasizes constructor-supplied Strategy and Player.play delegation; does not switch to State/Singleton despite those distractors in the retrieved evidence. |
| 1 / 0 | STOP → STOP | **Execution failure**, not a correct answer. `Strategy 예제의 구성은?` had full Strategy.java evidence plus a NightState distractor but timed out. Total turn latency 126,768 ms, including planning/retrieval. |

Safe failure: `Codex 답변 생성 단계에서 제한 시간을 초과했습니다.` The diagnostic is `transport=CODEX_APP_SERVER`, `phase=WAIT_RESPONSE`, `deadline=APP_SERVER`, `outcome=FAILED`. Under the implemented state machine this means a valid turn/start response was accepted, but a valid completed answer was not observed before the deadline. It does **not** reveal whether the remote model, transport delivery, notifications or another component caused the stall. It is not an initialize/config/thread/turn-acknowledgement timeout.

All three preceding PlanModel executions completed successfully through **Codex exec**, with STOP and no additional local search. Thus this batch distinguishes functioning exec planning from the failing app-server answer; it does not independently validate internal exec phases.

**The platform-thread fix does not eliminate all real-provider timeouts.** The constrained-carrier fake regression remains valid evidence of the repaired scheduling hazard, not proof that earlier real failures had only that cause. Do not increase timeouts or infer a specific provider model/rate limit from this result.

E01 is **not a completed three-variant acceptance PASS on this artifact**. The earlier one-trial PASS recommendation must not substitute for the missing repetitions. Full answers, evidence, thread IDs, safe phase and dispatch counts are preserved in the raw report.

## Batch 7 — E03/E02

Review completed: **actual execution failure, not pending review and not an acceptance PASS**. Expected: the setup plan completes before proceeding to its answer and the explicit topic-switch/call-chain turns. Actual: the setup plan returned FAILED with no decision/output; no answer exists to assess for correctness. E03 and E02 remain unexercised beyond E03's failed setup attempt. Reviewer and artifact/model-observation limitations are those stated above; the raw report is unchanged.

Independent authorized batch, maximum 8 calls. [Raw report](investigation-live-batch7-e03-e02-20261025.json). Actual **1 plan dispatch, 0 answers**; the first E03 setup question (`Strategy 패턴 예제에서 Player는 어떻게 동작해?`) failed at the 90-second investigation deadline. Diagnostic: `CODEX_EXEC / phase=UNOBSERVED / INVESTIGATION_90S`. The exec transport's internal progress was not available in that artifact. No local search, topic-switch turn, E02 call-chain question or answer was reached. No automatic rerun of batch 6 or 7.

## Release and acceptance limitation

This JAR's two batches total **7 dispatches: 4 plans and 3 answers**. Two answers are manually correct; one app-server answer timed out; one exec planning attempt timed out. The remaining three plans succeeded. All owned processes and temporary synthetic databases were cleaned up; JAR/Maven leases released. No complete three-variant E01 PASS, E03 PASS or E02 PASS is warranted. Historical one-trial evidence is not a substitute for the repetition requirement.
