import assert from 'node:assert/strict'
import test from 'node:test'
import { executionLedger, summarize } from './acceptance-execution-report.mjs'

const probe = (id = 'T0101', checkStatus = 'PASS') => ({ id, checkStatus, scope: 'TEST_BOUNDARY', startedAt: '2026-10-27T00:00:00Z', remaining: ['Full UI'], fullAcceptancePassed: false })

test('bounded passes stay PARTIAL and absent tests stay NOT_RUN', () => {
  const cases = executionLedger([], [probe()])
  assert.equal(cases.length, 1000)
  assert.deepEqual(summarize(cases), { PASS: 0, PARTIAL: 1, FAIL: 0, BLOCKED: 0, NOT_RUN: 974, EXCLUDED: 25 })
})

test('failed probes remain failures, prior evidence is retained without double counting', () => {
  const previous = [{ id: 'T0001', status: 'PARTIAL' }, { id: 'T0101', status: 'PARTIAL' }]
  assert.deepEqual(summarize(executionLedger(previous, [probe('T0101', 'FAIL')])),
    { PASS: 0, PARTIAL: 1, FAIL: 1, BLOCKED: 0, NOT_RUN: 973, EXCLUDED: 25 })
})

test('unknown, duplicate, unevidenced or promoted probes are rejected', () => {
  for (const probes of [[probe('T1001')], [probe(), probe()], [{ ...probe(), fullAcceptancePassed: true }],
    [{ ...probe(), remaining: [] }], [{ ...probe(), checkStatus: 'SKIPPED' }]]) {
    assert.throws(() => executionLedger([], probes))
  }
})

test('removed service failures remain historical evidence and never become passes', () => {
  const cases = executionLedger([], [probe('T0911', 'FAIL')])
  const removed = cases.find(item => item.id === 'T0911')
  assert.equal(removed.status, 'EXCLUDED')
  assert.equal(removed.previousObservation.checkStatus, 'FAIL')
  assert.equal(removed.fullAcceptancePassed, false)
  assert.deepEqual(executionLedger(cases, []).find(item => item.id === 'T0911'), removed)
})
