import assert from 'node:assert/strict'
import test from 'node:test'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { scenario } from './expert-live-memory-fixtures.mjs'
import { scenarios } from './expert-live-local-fixtures.mjs'
import { scheduledDispatches, createVariantBudget, validateMemoryContext, memoryAnswerCheck, requireMemoryPlan, validateMemoryPrompt } from './expert-live-schedule.mjs'

test('schedule counts actual turns, preserving two-turn batches and charging 64 per memory variant', () => {
  assert.equal(scheduledDispatches(scenarios, 0, 3, 2), 24)
  assert.equal(scheduledDispatches([scenario], 0, 1, 2), 64)
  assert.equal(scheduledDispatches([scenario], 1, 1, 2), 64)
  assert.equal(scheduledDispatches([scenario], 0, 3, 2), 192)
})

test('live entrypoint rejects insufficient cap or multiple variants before runtime or credentials', () => {
  const environment = Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.startsWith('FORGE_EXPERT_')))
  for (const settings of [{ FORGE_EXPERT_MAX_CALLS: '63', FORGE_EXPERT_VARIANTS: '1' },
    { FORGE_EXPERT_MAX_CALLS: '192', FORGE_EXPERT_VARIANTS: '3' },
    { FORGE_EXPERT_MAX_CALLS: '64', FORGE_EXPERT_VARIANTS: '1' }]) {
    const result = spawnSync(process.execPath, [fileURLToPath(new URL('./expert-live-eval.mjs', import.meta.url))], {
      env: { ...environment, ...settings, FORGE_EXPERT_MODE: 'memory', FORGE_EXPERT_LIVE: 'not-approved' }, encoding: 'utf8', timeout: 5000,
    })
    assert.equal(result.status, 1)
    assert.match(result.stderr, /bounded batch|one separately authorized|Separate finite variant authorization/)
    assert.doesNotMatch(result.stdout, /Isolated backend/)
  }
})

test('all 32 turns require counted distinct plan/answer POSTs; no replay or POST after cap', () => {
  const budget = createVariantBudget(64)
  const requests = []
  const post = (path, body) => { budget.beforePost(path, body); requests.push({ path, body }) }
  assert.throws(() => post('/investigation-plans/jobs', { requestId: 'uncounted' }))
  for (let turn = 0; turn < 32; turn++) {
    post('/chat-threads', { question: scenario.questions[0][turn] })
    for (const kind of ['plan', 'answer']) {
      const path = kind === 'plan' ? '/investigation-plans/jobs' : '/evidence-bundles/bundle/answer-jobs'
      const requestId = `${kind}-${turn}`
      budget.reserve(kind, requestId)
      assert.throws(() => post(path, { requestId: 'wrong' }))
      post(path, { requestId })
      assert.throws(() => post(path, { requestId }))
    }
  }
  assert.equal(budget.count, 64)
  assert.equal(requests.length, 96)
  assert.throws(() => budget.reserve('plan', 'extra'))
  assert.throws(() => post('/answer-preview', {}))
  assert.throws(() => post('/chat-threads', {}))
})

function contextFixture() {
  const prior = scenario.questions[0].slice(0, 31).map((question, turn) => ({
    bundle: { id: `bundle-${turn}`, digest: `digest-${turn}`, payload: { question } }, answer: '합성 승인 검토 설명',
  }))
  const extractive = { version: 1, turnKind: 'USER_QUESTION', totalQuestions: 31, omittedQuestions: 0,
    maxSerializedCharacters: 8000, answerCharacterLimit: 800, omittedAnswers: 0, truncatedAnswerBundleIds: [],
    questions: prior.map(({ bundle }) => ({ bundleId: bundle.id, bundleDigest: bundle.digest,
      question: bundle.payload.question, legacyUnclassified: false })), retrievalQueries: ['정책'] }
  const history = prior.slice(-5).map(({ bundle, answer }) => ({ bundleId: bundle.id, bundleDigest: bundle.digest,
    question: bundle.payload.question, answer }))
  return { prior, bundle: { payload: { chatContext: { extractive, history } } } }
}

test('final extraction binds exact source IDs/digests and correction beyond recent five, without fabricated compaction', () => {
  const { prior, bundle } = contextFixture()
  const metric = validateMemoryContext(bundle, prior, scenario, 31)
  assert.equal(metric.correctionIncluded, true)
  assert.equal(metric.outsideRecentFive, true)
  assert.equal(metric.correctionBundleId, 'bundle-24')
  assert.equal(metric.actualCompactionObserved, false)
  assert.equal(metric.fullE05Pass, false)
  const context = bundle.payload.chatContext
  const json = JSON.stringify({ extractive: context.extractive, recentAnswers: context.history })
  assert.equal(validateMemoryPrompt(`PREVIOUS_CONVERSATION_JSON:\n${json}\nCURRENT_REQUEST:\nquestion`, context, 8000), json)
  assert.equal(validateMemoryPrompt('Planning instructions\nREFERENCE_JSON:\n' + JSON.stringify({
    referenceRequest: `PREVIOUS_CONVERSATION_JSON:\n${json}\nCURRENT_REQUEST:\nquestion`,
  }), context, 8000), json)
  assert.throws(() => validateMemoryPrompt(`PREVIOUS_CONVERSATION_JSON:\n{}\nCURRENT_REQUEST:`, context, 8000))
  assert.throws(() => validateMemoryPrompt(`PREVIOUS_CONVERSATION_JSON:\n${json}\nCURRENT_REQUEST:`, context, 10))
})

test('omitted correction is fixture invalid, while ID/digest/order/budget mismatches stop verification', () => {
  const { prior, bundle } = contextFixture()
  const omitted = structuredClone(bundle)
  omitted.payload.chatContext.extractive.questions = omitted.payload.chatContext.extractive.questions.slice(25)
  omitted.payload.chatContext.extractive.omittedQuestions = 25
  assert.throws(() => validateMemoryContext(omitted, prior, scenario, 31), { code: 'FIXTURE_INVALID' })
  for (const change of [context => { context.extractive.questions[24].bundleDigest = 'wrong' },
    context => { context.extractive.questions.reverse() }, context => { context.history[0].bundleId = 'wrong' },
    context => { context.extractive.maxSerializedCharacters = 9000 }, context => { context.extractive.retrievalQueries = ['x'.repeat(8000)] }]) {
    const changed = structuredClone(bundle)
    change(changed.payload.chatContext)
    assert.throws(() => validateMemoryContext(changed, prior, scenario, 31))
  }
})

test('actual answer clipping is measured against original answers, not assumed from window size', () => {
  const { prior, bundle } = contextFixture()
  bundle.payload.chatContext.history[0].answer = null
  bundle.payload.chatContext.extractive.truncatedAnswerBundleIds = ['bundle-26']
  bundle.payload.chatContext.extractive.omittedAnswers = 1
  const metric = validateMemoryContext(bundle, prior, scenario, 31)
  assert.equal(metric.removedAnswerCharacters, prior[26].answer.length)
  assert.equal(metric.actualCompactionObserved, true)
  assert.equal(metric.fullE05Pass, false)
})

test('failed/uncertain/proposed/search plans and wrong-target or irrelevant answers stop without retry', () => {
  const plan = { status: 'SUCCEEDED', decision: 'STOP', query: null, output: null, toolStatus: null }
  requireMemoryPlan(plan)
  for (const change of [{ status: 'FAILED' }, { status: 'RUNNING' }, { decision: 'SEARCH' },
    { decision: 'PROPOSE_MYSQL_READ' }, { decision: 'PROPOSE_DATADOG_LOGS' }, { query: 'ORBIT staging' }]) {
    assert.throws(() => requireMemoryPlan({ ...plan, ...change }))
  }
  assert.equal(memoryAnswerCheck('VEGA production', scenario, 31, 0), true)
  for (const answer of ['', 'ORBIT staging', 'VEGA staging', 'irrelevant']) {
    assert.equal(memoryAnswerCheck(answer, scenario, 31, 0), false)
  }
  assert.equal(memoryAnswerCheck('승인 검토', scenario, 1, 0), true)
  assert.equal(memoryAnswerCheck('irrelevant', scenario, 1, 0), false)
})
