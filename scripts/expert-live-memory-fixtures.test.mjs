import assert from 'node:assert/strict'
import test from 'node:test'
import { documents, scenario } from './expert-live-memory-fixtures.mjs'

test('offline E05 matrix is bounded, distinct and keeps correction outside recent-five window', () => {
  assert.equal(scenario.requiresFiniteAuthorization, true)
  assert.equal(scenario.questions.length, 3)
  assert.equal(scenario.maximumContextCharacters, 8000)
  assert.equal(new Set(scenario.questions.map(questions => JSON.stringify(questions))).size, 3)
  for (const questions of scenario.questions) {
    assert.equal(questions.length, scenario.maximumQuestions)
    assert.equal(questions.length * 2, scenario.maximumDispatchesPerVariant)
    assert.match(questions[0], /ORBIT/)
    assert.match(questions[0], /staging/)
    assert.match(questions[scenario.correctionTurn], /VEGA/)
    assert.match(questions[scenario.correctionTurn], /production/)
    assert.ok(scenario.probeTurn - scenario.correctionTurn > 5)
    for (const question of questions.slice(scenario.correctionTurn + 1)) {
      assert.doesNotMatch(question, /VEGA|production|ORBIT|staging/)
    }
    assert.ok(questions.every(question => question.length <= 256))
    assert.ok(questions.join('\n').length < scenario.maximumContextCharacters)
  }
})

test('synthetic source defines alternatives but never fixes active selection or operational facts', () => {
  const source = Object.values(documents).join('\n')
  assert.match(source, /실제 시스템이나 레코드는 존재하지 않는다/)
  assert.match(source, /현재 선택값.*없다/)
  assert.doesNotMatch(source, /https?:\/\/|SELECT |UPDATE |현재 대상은 VEGA|현재 환경은 production/)
  assert.deepEqual(scenario.expectedFinal, { target: 'VEGA', environment: 'production' })
  assert.ok(scenario.manualCriteria.some(criterion => criterion.includes('not semantic summarization')))
})
