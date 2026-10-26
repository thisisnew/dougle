import assert from 'node:assert/strict'

export function scheduledDispatches(scenarios, variantStart, variants, callsPerTurn) {
  return scenarios.reduce((total, scenario) => total + scenario.questions.slice(variantStart, variantStart + variants)
    .reduce((sum, questions) => sum + questions.length * callsPerTurn, 0), 0)
}

export function createVariantBudget(limit) {
  assert.ok(Number.isSafeInteger(limit) && limit > 0)
  let count = 0
  let pending = null
  const ids = new Set()
  return {
    reserve(kind, requestId) {
      assert.ok(count < limit && !pending && !ids.has(requestId), 'Variant dispatch cap or duplicate reservation; no retry')
      assert.ok(['plan', 'answer'].includes(kind))
      ids.add(requestId)
      count++
      pending = { kind, requestId }
    },
    beforePost(path, body) {
      const kind = /\/investigation-plans\/jobs$/.test(path) ? 'plan' : /\/answer-jobs$/.test(path) ? 'answer' : null
      if (kind) {
        assert.deepEqual(pending, { kind, requestId: body?.requestId }, 'Job POST requires unique counted reservation; no replay')
        pending = null
      } else assert.ok(count < limit && !pending, 'Variant cap reached; no further POST')
    },
    get count() { return count },
  }
}

export function validateMemoryContext(bundle, prior, scenario, turn) {
  const context = bundle.payload.chatContext
  const extractive = context.extractive
  function invalid(message) { throw Object.assign(Error(message), { code: 'FIXTURE_INVALID' }) }
  if (!extractive || extractive.version !== 1 || extractive.turnKind !== 'USER_QUESTION') invalid('Expected versioned user-question extraction')
  assert.equal(extractive.maxSerializedCharacters, scenario.maximumContextCharacters)
  assert.equal(extractive.totalQuestions, prior.length)
  assert.ok(extractive.questions.length <= 32)
  assert.equal(extractive.omittedQuestions, prior.length - extractive.questions.length)
  const retained = prior.slice(prior.length - extractive.questions.length)
  assert.deepEqual(extractive.questions, retained.map(result => ({ bundleId: result.bundle.id,
    bundleDigest: result.bundle.digest, question: result.bundle.payload.question, legacyUnclassified: false })))
  const recent = prior.slice(-5)
  assert.ok(context.history.length <= 5)
  let lastPosition = -1
  for (const item of context.history) {
    const position = recent.findIndex(result => result.bundle.id === item.bundleId)
    assert.ok(position > lastPosition && position >= 0, 'Recent answer source IDs/order mismatch')
    lastPosition = position
    const source = recent[position]
    assert.equal(item.bundleDigest, source.bundle.digest)
    assert.equal(item.question, source.bundle.payload.question)
    assert.ok(item.answer === null || source.answer.startsWith(item.answer))
    assert.ok((item.answer?.length ?? 0) <= extractive.answerCharacterLimit)
  }
  const serialized = JSON.stringify({ extractive, recentAnswers: context.history })
  assert.ok(serialized.length <= scenario.maximumContextCharacters, 'Serialized context exceeds budget')
  const removedAnswerCharacters = recent.reduce((sum, result) => sum + result.answer.length
    - (context.history.find(item => item.bundleId === result.bundle.id)?.answer?.length ?? 0), 0)
  const truncated = recent.filter(result => result.answer.length
    > (context.history.find(item => item.bundleId === result.bundle.id)?.answer?.length ?? 0))
  assert.deepEqual(extractive.truncatedAnswerBundleIds, truncated.map(result => result.bundle.id))
  assert.equal(extractive.omittedAnswers, recent.filter(result => result.answer.length > 0
    && !context.history.find(item => item.bundleId === result.bundle.id)?.answer).length)
  const metric = { serializedCharacters: serialized.length, maxSerializedCharacters: extractive.maxSerializedCharacters,
    retainedQuestionBundleIds: extractive.questions.map(item => item.bundleId), omittedQuestions: extractive.omittedQuestions,
    omittedQuestionCharacters: prior.slice(0, extractive.omittedQuestions).reduce((sum, result) => sum + result.bundle.payload.question.length, 0),
    removedAnswerCharacters, actualCompactionObserved: removedAnswerCharacters > 0 || extractive.omittedQuestions > 0,
    fullE05Pass: false, scope: 'Single-variant extractive evidence only; full E05 requires three variants plus a separate reviewed loss-awareness negative' }
  if (turn === scenario.probeTurn) {
    const correction = prior[scenario.correctionTurn].bundle
    metric.correctionBundleId = correction.id
    metric.correctionBundleDigest = correction.digest
    metric.correctionIncluded = extractive.questions.some(item => item.bundleId === correction.id)
    metric.outsideRecentFive = !recent.some(result => result.bundle.id === correction.id)
    if (!metric.correctionIncluded) invalid('Correction omitted from bounded extraction; fixture invalid, not model wrong')
    assert.equal(metric.outsideRecentFive, true)
    assert.ok(!context.history.some(item => item.bundleId === correction.id))
  }
  return metric
}

export function requireMemoryPlan(plan) {
  assert.equal(plan.status, 'SUCCEEDED', 'Memory planning failed or uncertain; no retry')
  assert.equal(plan.decision, 'STOP', 'Memory fixture forbids search and operational proposal; stop')
  for (const key of ['query', 'output', 'toolStatus']) assert.equal(plan[key], null)
}

export function validateMemoryPrompt(prompt, context, maximumCharacters) {
  const marker = 'REFERENCE_JSON:\n'
  const reference = prompt.includes(marker) ? JSON.parse(prompt.slice(prompt.indexOf(marker) + marker.length)).referenceRequest : prompt
  assert.equal(typeof reference, 'string')
  const match = reference.match(/PREVIOUS_CONVERSATION_JSON:\s*([\s\S]*?)\nCURRENT_REQUEST:/)
  assert.ok(match, 'Actual answer prompt lacks extractive context')
  assert.ok(match[1].length <= maximumCharacters)
  assert.deepEqual(JSON.parse(match[1]), { extractive: context.extractive, recentAnswers: context.history })
  return match[1]
}

export function memoryAnswerCheck(answer, scenario, turn, variant) {
  if (!answer.trim()) return false
  if (turn < scenario.correctionTurn && /VEGA|production/i.test(answer)) return false
  if (turn >= scenario.correctionTurn && /ORBIT|staging/i.test(answer)) return false
  if (turn === 0) return /ORBIT/.test(answer) && /staging/.test(answer)
  if (turn === scenario.correctionTurn || turn === scenario.probeTurn) return /VEGA/.test(answer) && /production/.test(answer)
  const question = scenario.questions[variant][turn]
  const term = ['라벨', '승인', '만료'].find(value => question.includes(value))
  return !!term && answer.includes(term)
}
