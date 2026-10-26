import { beforeEach, expect, it } from 'vitest'
import { readChatSubmission, removeChatSubmission, saveChatSubmission } from './chatSubmissionStorage'

const saved = { workspaceId: 'one', bundleId: 'bundle', bundleDigest: 'frozen', provider: 'CODEX', requestedModel: 'fixture-model',
  submission: { requestId: 'request-one', approvalId: 'approval', digest: 'preview', approved: true as const } }
beforeEach(() => {
  for (const key of Object.keys(sessionStorage)) if (key.startsWith('dougle.chat-submission.')) sessionStorage.removeItem(key)
})

it('질문·답변·다른 속성 없이 접수 식별자와 범위만 보관한다', () => {
  saveChatSubmission({ ...saved, ...{ question: 'synthetic question', answer: 'synthetic answer', secret: 'synthetic secret' },
    submission: { ...saved.submission, ...{ extra: 'synthetic extra' } } })
  const key = Object.keys(sessionStorage).find(value => value.startsWith('dougle.chat-submission.'))!
  expect(JSON.parse(sessionStorage.getItem(key)!)).toEqual(saved)
  expect(readChatSubmission(saved)).toEqual(saved)
})

it('서재·묶음별로 분리하고 digest가 달라지면 원래 접수를 보존한다', () => {
  saveChatSubmission(saved)
  expect(readChatSubmission({ ...saved, workspaceId: 'two' })).toBeNull()
  expect(readChatSubmission({ ...saved, bundleId: 'other' })).toBeNull()
  expect(() => readChatSubmission({ ...saved, bundleDigest: 'changed' })).toThrow('접수 정보를 확인하지 못했습니다')
  expect(readChatSubmission(saved)).toEqual(saved)
})

it('미확인 접수는 다른 요청으로 덮어쓰거나 삭제하지 않는다', () => {
  saveChatSubmission(saved)
  saveChatSubmission(saved)
  const other = { ...saved, submission: { ...saved.submission, requestId: 'request-two' } }
  expect(() => saveChatSubmission(other)).toThrow('접수가 남아 있습니다')
  removeChatSubmission(other)
  expect(readChatSubmission(saved)).toEqual(saved)
})

it('확인된 접수만 지우고 다른 접수와 앱 저장소를 보존한다', () => {
  const other = { ...saved, bundleId: 'other' }
  sessionStorage.setItem('synthetic-unrelated', 'keep')
  saveChatSubmission(saved)
  saveChatSubmission(other)
  removeChatSubmission(saved)
  expect(readChatSubmission(saved)).toBeNull()
  expect(readChatSubmission(other)).toEqual(other)
  expect(sessionStorage.getItem('synthetic-unrelated')).toBe('keep')
  sessionStorage.removeItem('synthetic-unrelated')
})

it.each(['{broken', JSON.stringify({ ...saved, submission: { ...saved.submission, approved: false } })])('손상된 접수를 삭제하거나 새 요청으로 바꾸지 않는다: %s', raw => {
  saveChatSubmission(saved)
  const key = Object.keys(sessionStorage).find(value => value.startsWith('dougle.chat-submission.'))!
  sessionStorage.setItem(key, raw)
  expect(() => readChatSubmission(saved)).toThrow('접수 정보를 확인하지 못했습니다')
  expect(() => saveChatSubmission(saved)).toThrow('접수 정보를 확인하지 못했습니다')
  expect(sessionStorage.getItem(key)).toBe(raw)
})
