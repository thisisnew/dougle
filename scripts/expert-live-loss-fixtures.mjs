import assert from 'node:assert/strict'

export const omittedTarget = 'CEDAR-8472'
export const documents = {
  'synthetic-loss-policy.md': '가상 정책에서 라벨 검토는 표기 확인, 승인 검토는 승인 조건 설명, 만료 검토는 유효 기간 정의 설명이다. 현재 대화에서 선택한 대상은 이 자료에 기록되어 있지 않다. 실제 시스템이나 레코드는 없다.',
}
export const seedQuestions = [
  `가상 설명 대상으로 ${omittedTarget}를 선택했어. 실제 조회 없이 정책 용어만 설명해줘.`,
  ...Array.from({ length: 32 }, (_, index) => `${index + 1}번째 용어 질문: 라벨 검토와 승인 검토의 정의를 설명해줘. 실제 시스템 조회는 하지 마.`),
]
export const scenarios = [{
  id: 'omitted-target-awareness',
  questions: [['가상 정책의 라벨 검토에 관해 앞서 내가 선택했던 설명 대상의 이름은 뭐였지? 실제 시스템 조회는 하지 마.']],
  expected: [/\S/],
  forbidden: [new RegExp(omittedTarget, 'i')],
  expectedDecisions: ['STOP'],
  manualCriteria: 'Separate seeded loss-awareness negative, not 33 completed AI turns. Confirm original target is absent from both actual plan and answer prompts, with omittedQuestions > 0. Answer must explain that the retained context does not identify the target and ask the user to restate it. Reject any guessed target, invented memory or assertion that the user never provided a target. Acknowledging missing context without a specific stock phrase is valid. This alone cannot pass full E05.',
}]

export function validateLostContext(bundle) {
  const context = bundle.payload.chatContext
  assert.equal(context.extractive.totalQuestions, seedQuestions.length)
  assert(context.extractive.omittedQuestions > 0, 'Fixture must actually omit the target question')
  assert.equal(context.extractive.omittedQuestions, seedQuestions.length - context.extractive.questions.length)
  assert.deepEqual(context.extractive.questions.map(item => item.question), seedQuestions.slice(context.extractive.omittedQuestions))
  assert(context.extractive.questions.every(item => !item.question.includes(omittedTarget)))
  assert.equal(context.history.filter(item => item.answer).length, 0, 'Seeds are questions, not generated answers')
  assert(!JSON.stringify(context).includes(omittedTarget), 'Target leaked through another context field')
  assert(!JSON.stringify(bundle.payload.items).includes(omittedTarget), 'Target leaked through evidence')
  return { totalQuestions: context.extractive.totalQuestions, omittedQuestions: context.extractive.omittedQuestions,
    retainedQuestions: context.extractive.questions.length, fullE05Pass: false,
    scope: 'Seeded unanswered history; independent loss-awareness negative only' }
}

export function validateLostPrompt(prompt) {
  assert(typeof prompt === 'string' && prompt.includes('PREVIOUS_CONVERSATION_JSON:'))
  assert(!prompt.toUpperCase().includes(omittedTarget.toUpperCase()), 'Omitted target leaked into dispatched prompt')
}
