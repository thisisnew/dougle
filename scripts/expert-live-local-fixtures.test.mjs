import assert from 'node:assert/strict'
import test from 'node:test'
import { documents, scenarios } from './expert-live-local-fixtures.mjs'

test('two bounded three-variant cases cost at most twenty-four dispatches', () => {
  assert.equal(scenarios.length, 2)
  let dispatches = 0
  for (const scenario of scenarios) {
    assert.equal(scenario.questions.length, 3)
    assert.equal(new Set(scenario.questions.map(pair => JSON.stringify(pair))).size, 3)
    for (const pair of scenario.questions) {
      assert.equal(pair.length, 2)
      for (const question of pair) assert.ok(question.length <= 256)
      dispatches += pair.length * 2
    }
  }
  assert.equal(dispatches, 24)
  assert.equal(scenarios[0].restartBeforeTurn, 1)
  assert.equal(scenarios[1].restartBeforeTurn, undefined)
})

test('synthetic source definitions separate synonyms from wrong-domain states', () => {
  assert.match(documents['synthetic-badge-policy.md'], /72시간/)
  assert.match(documents['synthetic-badge-policy.md'], /자동 갱신되지 않는다/)
  assert.match(documents['synthetic-glossary.md'], /입고와 입하는.*동일/)
  assert.match(documents['synthetic-glossary.md'], /서로 다른 업무이며 동의어가 아니다/)
  for (const body of Object.values(documents)) assert.doesNotMatch(body, /https?:\/\/|manualCriteria|expectedDecisions|SELECT |UPDATE /)
})
