import assert from 'node:assert/strict'
import test from 'node:test'
import { requireSuccessfulAnswer, restartAndValidate, assertThreadExtends, manualCriteriaFor } from './expert-live-history.mjs'
import { scenarios } from './expert-live-local-fixtures.mjs'
import { scheduledDispatches } from './expert-live-schedule.mjs'

test('non-hold manual criteria remain unchanged for existing fixtures', () => {
  for (const scenario of scenarios) {
    assert.equal(manualCriteriaFor(scenario), scenario.manualCriteria)
    assert.equal(manualCriteriaFor(scenario, false), scenario.manualCriteria)
  }
  assert.match(manualCriteriaFor(scenarios[0]), /backend-only.*UI reopen not exercised/)
})

test('E04 hold criteria require same-trial UI reopening without submission, not an unobserved-UI claim', () => {
  const scenario = scenarios.find(item => item.id === 'restart-history-followup')
  const before = structuredClone(scenario)
  const criteria = manualCriteriaFor(scenario, true)
  assert.match(criteria, /UI reopening.*REQUIRED before followup/)
  assert.match(criteria, /screenshot.*bound acknowledgment without submitting/)
  assert.match(criteria, /no new jobs or changes/)
  assert.match(criteria, /API-driven, not UI-submitted/)
  assert.match(criteria, /native desktop bootstrap is not tested/)
  assert.doesNotMatch(criteria, /backend-only|UI reopen not exercised/)
  assert.deepEqual(scenario, before)
  assert.equal(scheduledDispatches([scenario], 0, 3, 2), 12)
  assert.throws(() => manualCriteriaFor(scenarios[1], true), /E04 restart fixture/)
})

test('automatic false, missing evidence and failed/uncertain answers stop execution', () => {
  requireSuccessfulAnswer({ status: 'SUCCEEDED', automaticPass: true })
  for (const result of [{ status: 'SUCCEEDED', automaticPass: false }, { status: 'FAILED', automaticPass: true },
    { status: 'CANCELLED', automaticPass: false }, { status: 'RUNNING', automaticPass: false }, { status: 'NO_EVIDENCE' }]) {
    assert.throws(() => requireSuccessfulAnswer(result))
  }
})

test('restart happens only after confirmed shutdown and compares complete snapshot', async () => {
  const events = []
  const before = { history: [{ id: 'thread' }], detail: { bundles: [{ id: 'first', digest: 'fixed' }], jobs: [{ id: 'job', answer: 'synthetic' }] } }
  await restartAndValidate({ stop: async () => events.push('stop'), start: async () => events.push('start'),
    snapshot: async () => { events.push('read'); return structuredClone(before) } }, before)
  assert.deepEqual(events, ['stop', 'start', 'read'])
  for (const changed of [{ ...before, history: [] }, { ...before, detail: { bundles: [], jobs: [] } }]) {
    await assert.rejects(restartAndValidate({ stop: async () => {}, start: async () => {}, snapshot: async () => changed }, before))
  }
})

test('shutdown and startup failures never cause automatic retry', async () => {
  let starts = 0
  await assert.rejects(restartAndValidate({ stop: async () => { throw Error('shutdown failed') },
    start: async () => starts++, snapshot: async () => {} }, {}), /shutdown failed/)
  assert.equal(starts, 0)
  await assert.rejects(restartAndValidate({ stop: async () => {}, start: async () => { starts++; throw Error('startup failed') },
    snapshot: async () => assert.fail('must not read') }, {}), /startup failed/)
  assert.equal(starts, 1)
})

test('followup preserves prior evidence/job and binds latest parent', () => {
  const before = { id: 'thread', bundles: [{ id: 'first', digest: 'fixed' }], jobs: [{ id: 'job', answer: 'synthetic' }] }
  const after = { ...structuredClone(before), bundles: [...before.bundles,
    { id: 'next', payload: { chatContext: { parentBundleId: 'first' } } }] }
  assertThreadExtends(before, after)
  for (const mutate of [value => { value.id = 'other' }, value => { value.bundles[0].digest = 'changed' },
    value => { value.jobs = [] }, value => { value.bundles[1].payload.chatContext.parentBundleId = 'wrong' }]) {
    const changed = structuredClone(after)
    mutate(changed)
    assert.throws(() => assertThreadExtends(before, changed))
  }
})
