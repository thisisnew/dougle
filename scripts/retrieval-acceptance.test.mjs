import assert from 'node:assert/strict'
import test from 'node:test'
import { score, questions, artifactHash } from './retrieval-acceptance.mjs'

test('retrieval score separates ranking, evidence and negative abstention', () => {
  const query = { expected: ['correct.java'], phrase: 'important body' }
  const result = score([{ title: 'wrong.java', quote: 'important body' }, { title: 'correct.java', quote: 'missing' }], query)
  assert.equal(result.hitAt1, false)
  assert.equal(result.hitAt5, true)
  assert.equal(result.precisionReturned, 0.5)
  assert.equal(result.evidencePresent, false)
  assert.equal(score([], { expected: [] }).negativeAbstention, true)
  assert.equal(score([{ title: 'wrong', quote: '' }], { expected: [] }).negativeAbstention, false)
  const collision = score([{ title: 'Main.java', sourcePath: 'factory/Main.java', quote: '' }], { expected: [], expectedPath: '/strategy/' })
  assert.equal(collision.hitAt5, false)
  assert.equal(collision.negativeAbstention, null)
})

test('fixture has distinct exact paraphrase cross-language and negative questions', () => {
  assert.equal(new Set(questions.map(item => item.id)).size, questions.length)
  assert(questions.some(item => item.id.includes('paraphrase')))
  assert(questions.some(item => item.id.includes('cross-language')))
  assert.equal(questions.filter(item => !item.expected.length).length, 2)
  assert.match(artifactHash, /^[a-f0-9]{64}$/)
})
