import assert from 'node:assert/strict'

export function manualCriteriaFor(scenario, holdForUi = false) {
  if (!holdForUi) return scenario.manualCriteria
  assert.equal(scenario.id, 'restart-history-followup', 'UI hold criteria require the E04 restart fixture')
  assert.equal(scenario.restartBeforeTurn, 1)
  return 'E04: restart the same synthetic DB after a completed answer; preserve thread/evidence/jobs/order. '
    + 'Parent Ego UI reopening of the exact persisted history row is REQUIRED before followup: inspect the prior question, answer, citations and order; '
    + 'save a synthetic screenshot and exact bound acknowledgment without submitting. UI GETs and persisted-state checks must show no new jobs or changes. '
    + 'Only after acknowledgment may the harness submit the same-thread followup and verify manual operator approval as the answer. '
    + 'Followup is API-driven, not UI-submitted; native desktop bootstrap is not tested. '
    + 'These are required criteria, not a claim that the checkpoint has already been observed.'
}

export function requireSuccessfulAnswer(result) {
  assert.equal(result.status, 'SUCCEEDED', 'Answer did not succeed; no retry')
  assert.equal(result.automaticPass, true, 'Automatic semantic check failed; stop without retry')
}

export async function restartAndValidate({ stop, start, snapshot }, before) {
  await stop()
  await start()
  const after = await snapshot()
  assert.deepEqual(after, before, 'Restart changed history, evidence, jobs or order')
  return after
}

export function assertThreadExtends(before, after) {
  assert.equal(after.id, before.id)
  assert.ok(after.bundles.length > before.bundles.length)
  assert.deepEqual(after.bundles.slice(0, before.bundles.length), before.bundles)
  for (const job of before.jobs) assert.deepEqual(after.jobs.find(candidate => candidate.id === job.id), job)
  assert.equal(after.bundles[before.bundles.length].payload.chatContext.parentBundleId, before.bundles.at(-1).id)
}
