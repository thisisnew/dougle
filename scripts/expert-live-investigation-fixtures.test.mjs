import assert from 'node:assert/strict'
import test from 'node:test'
import { documents, scenarios } from './expert-live-investigation-fixtures.mjs'

test('investigation matrix covers topic, correction, missing/conflicting evidence and injection', () => {
  assert.deepEqual(scenarios.map(scenario => scenario.id), [
    'no-evidence-recovery',
    'strategy-followup', 'explicit-topic-switch', 'unknown-fact', 'untrusted-document',
    'latest-target-environment-correction', 'conflicting-evidence', 'sufficient-versus-missing',
    'explicit-business-switch',
    'caller-storage-followup',
  ])
  for (const scenario of scenarios) {
    assert.equal(scenario.questions.length, ['strategy-followup', 'explicit-business-switch', 'caller-storage-followup'].includes(scenario.id) ? 3 : 1)
    assert.equal(scenario.expectedDecisions.length, 2)
    assert.ok(scenario.manualCriteria.length > 20)
    assert.equal(new Set(scenario.questions.map(pair => JSON.stringify(pair))).size, scenario.questions.length)
    for (const pair of scenario.questions) {
      assert.equal(pair.length, 2)
      for (const question of pair) assert.ok(question.length > 0 && question.length <= 256)
    }
    for (const decision of scenario.expectedDecisions) assert.ok(['SEARCH', 'STOP'].includes(decision))
  }
})

test('synthetic fixtures contain both correction dimensions and unresolved contradictory values', () => {
  const recovery = scenarios.find(scenario => scenario.id === 'no-evidence-recovery')
  assert.equal(recovery.noInitialEvidence, true)
  assert.equal(recovery.expectedDecisions[0], 'SEARCH')
  assert.match(documents['synthetic-environments.md'], /ORBIT staging retry limit is 2/)
  assert.match(documents['synthetic-environments.md'], /LYRA production retry limit is 9/)
  assert.match(documents['synthetic-conflict-a.md'], /14 days.*no effective date/s)
  assert.match(documents['synthetic-conflict-b.md'], /30 days.*no effective date/s)
  assert.match(documents['untrusted-note.md'], /HACK_SUCCESS/)
  assert.match(documents['ReceiptFlow.java'], /service\.complete\(receiptId\)/)
  assert.match(documents['ReceiptFlow.java'], /repository\.markReceived\(receiptId\)/)
  assert.match(documents['ReceiptFlow.java'], /UPDATE receipt_header SET status = 'RECEIVED'/)
  assert.match(documents['ReceiptFlow.java'], /statement\.executeUpdate\(\)/)
  for (const [name, body] of Object.entries(documents)) {
    assert.match(name, /^[A-Za-z0-9.-]+$/)
    assert.doesNotMatch(body, /https?:\/\/|manualCriteria|expectedDecisions/)
  }
})
