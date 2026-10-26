import assert from 'node:assert/strict'
import test from 'node:test'
import { documents, scenarios } from './expert-live-fixtures.mjs'

test('approved fixture has exactly thirty turns with distinct scenario variants', () => {
  assert.equal(scenarios.length, 5)
  assert.equal(new Set(scenarios.map(value => value.id)).size, 5)
  let count = 0
  for (const scenario of scenarios) {
    assert.equal(scenario.questions.length, 3)
    assert.equal(scenario.expected.length, 2)
    assert.equal(scenario.forbidden.length, 2)
    for (const pair of scenario.questions) {
      assert.equal(pair.length, 2)
      for (const question of pair) {
        assert.ok(question.length > 0 && question.length <= 256)
        count++
      }
    }
  }
  assert.equal(count, 30)
})

test('only explicit synthetic source files are imported; evaluator patterns remain separate', () => {
  for (const [name, body] of Object.entries(documents)) {
    assert.match(name, /^[A-Za-z0-9.-]+$/)
    assert.ok(body.length > 0)
    assert.doesNotMatch(body, /https?:\/\/|automaticPass|scenario\.expected/)
  }
  assert.match(documents['Strategy.java'], /strategy.nextMove/)
  assert.match(documents['NightState.java'], /NIGHT_ALERT/)
  assert.match(documents['untrusted-note.md'], /HACK_SUCCESS/)
})
