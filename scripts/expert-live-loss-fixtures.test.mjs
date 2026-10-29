import test from 'node:test'
import assert from 'node:assert/strict'
import { seedQuestions, omittedTarget, documents, validateLostContext, validateLostPrompt } from './expert-live-loss-fixtures.mjs'

function bundle() {
  return { payload: { items: [{ quote: Object.values(documents)[0] }], chatContext: {
    extractive: { totalQuestions: 33, omittedQuestions: 1, questions: seedQuestions.slice(1).map(question => ({ question })) },
    history: seedQuestions.slice(-5).map(question => ({ question, answer: null })), retrievalQuery: '앞서 선택한 대상',
  } } }
}

test('loss fixture contains one historical target and no independent source of the answer', () => {
  assert.equal(seedQuestions.length, 33)
  assert.equal(seedQuestions.filter(question => question.includes(omittedTarget)).length, 1)
  assert(!JSON.stringify(documents).includes(omittedTarget))
  assert.equal(validateLostContext(bundle()).fullE05Pass, false)
})

test('no omission, target retention, answer leakage, retrieval leakage and evidence leakage invalidate the probe', () => {
  const changes = [
    value => { value.payload.chatContext.extractive.omittedQuestions = 0 },
    value => { value.payload.chatContext.extractive.questions[0].question = omittedTarget },
    value => { value.payload.chatContext.history[0].answer = 'made-up seeded answer' },
    value => { value.payload.chatContext.retrievalQuery = omittedTarget },
    value => { value.payload.items[0].quote = omittedTarget },
  ]
  for (const change of changes) {
    const value = bundle(); change(value)
    assert.throws(() => validateLostContext(value))
  }
})

test('both raw answer and JSON-wrapped planning prompts must not expose omitted target', () => {
  const clean = 'PREVIOUS_CONVERSATION_JSON:\n' + JSON.stringify(bundle().payload.chatContext) + '\nCURRENT_REQUEST: target?'
  for (const prompt of [clean, JSON.stringify({ referenceRequest: clean })]) validateLostPrompt(prompt)
  for (const prompt of [clean + omittedTarget, JSON.stringify({ referenceRequest: clean + omittedTarget.toLowerCase() }), 'missing context']) {
    assert.throws(() => validateLostPrompt(prompt))
  }
})
