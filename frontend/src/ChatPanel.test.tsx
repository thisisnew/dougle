import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { StrictMode, useState, type ReactNode } from 'react'
import ChatPanel, { QuestionHistory } from './ChatPanel'
import { ApiError, request } from './api'
import AnswerJobsPanel from './AnswerJobsPanel'
import type { AnswerJob } from './answerJobEvents'
import { readChatSubmission, saveChatSubmission } from './chatSubmissionStorage'
import { readConversationPlan, sendConversationPlan } from './sendConversationPlan'
import { savePlanSubmission } from './planSubmissionStorage'
import * as ConversationReadModule from './ConversationReadPanel'

vi.mock('./api', async importOriginal => ({ ...await importOriginal<typeof import('./api')>(), request: vi.fn() }))
vi.mock('./sendConversationPlan', () => ({ sendConversationPlan: vi.fn(), readConversationPlan: vi.fn() }))
vi.mock('./AnswerJobsPanel', () => ({ default: vi.fn(({ initialJob, references }: { initialJob: AnswerJob; references: (answer: string) => ReactNode }) => <><p>{initialJob.answer}</p>{initialJob.status === 'SUCCEEDED' && references(initialJob.answer ?? '')}</>) }))
const api = vi.mocked(request)
const props = { workspaceId: 'one', selectedId: null, connection: { provider: 'CODEX' as const, state: 'AUTHENTICATED', model: 'fixture-model' }, onCreated: vi.fn(), onConnect: vi.fn(), onSources: vi.fn() }
const bundle = { id: 'bundle', workspaceId: 'one', digest: 'digest', payload: { question: '가상 질문', status: 'READY', chatContext: { threadId: 'thread' }, items: [{ kind: 'ORIGINAL', label: 'R1', title: '가상 자료', quote: '근거', startLine: 1, endLine: 2 }] } }
const prepared = { bundleId: 'bundle', bundleDigest: 'digest', provider: 'CODEX', requestedModel: 'fixture-model', digest: 'preview', validation: { eligible: true } }
const approved = { id: 'approval', bundleId: 'bundle', digest: 'preview', provider: 'CODEX', requestedModel: 'fixture-model' }
const completed = { id: 'job', bundleId: 'bundle', approvalId: 'approval', status: 'SUCCEEDED', provider: 'CODEX', requestedModel: 'fixture-model', answer: '가상 답변 [R1]', error: null }
const thread = { id: 'thread', workspaceId: 'one', bundles: [bundle], jobs: [{ ...completed, workspaceId: 'one' }] }
const planBinding = { provider: 'CODEX', requestedModel: 'fixture-model', digest: 'plan-digest' }
beforeEach(() => {
  api.mockReset(); vi.clearAllMocks(); vi.restoreAllMocks()
  vi.mocked(sendConversationPlan).mockReset().mockImplementation(async (workspaceId, bundleId) => ({ ...planBinding, id: 'plan', workspaceId, bundleId, status: 'SUCCEEDED', decision: 'STOP' }))
  vi.mocked(readConversationPlan).mockReset().mockResolvedValue(null)
  for (const key of Object.keys(sessionStorage)) if (key.startsWith('dougle.chat-submission.') || key.startsWith('dougle.plan-submission.')) sessionStorage.removeItem(key)
})

const completeContext = { version: 1, totalQuestions: 2, omittedQuestions: 0, maxSerializedCharacters: 6000,
  answerCharacterLimit: 800, omittedAnswers: 0, truncatedAnswerBundleIds: [],
  questions: [{ bundleId: 'previous', bundleDigest: 'a'.repeat(64), question: '이전 질문' }], retrievalQueries: ['이전 검색'] }
const contextBundle = (extractive: unknown) => ({ ...bundle, payload: { ...bundle.payload, status: 'NO_EVIDENCE', items: [],
  chatContext: { threadId: 'thread', extractive } } })

it('plans on explicit send before any answer, and pauses an operational proposal without blocking the composer', async () => {
  let resolve!: (value: Awaited<ReturnType<typeof sendConversationPlan>>) => void
  vi.mocked(sendConversationPlan).mockImplementation(() => new Promise(done => { resolve = done }))
  api.mockResolvedValueOnce(bundle)
  render(<ChatPanel {...props} />)
  expect(sendConversationPlan).not.toHaveBeenCalled()
  await userEvent.type(screen.getByRole('textbox'), '가상 업무 오류{Enter}')
  await waitFor(() => expect(sendConversationPlan).toHaveBeenCalledTimes(1))
  expect(api).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('textbox')).toBeDisabled()
  await act(async () => resolve({ ...planBinding, id: 'plan', workspaceId: 'one', bundleId: 'bundle', status: 'SUCCEEDED', decision: 'PROPOSE_MYSQL_READ' }))
  expect(await screen.findByText('운영 조회 제안 · 실행하지 않음')).toBeVisible()
  expect(screen.queryByRole('button', { name: '답변 받기' })).not.toBeInTheDocument()
  expect(screen.getByRole('textbox')).toBeEnabled()
  await userEvent.type(screen.getByRole('textbox'), '운영 환경이에요')
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeEnabled()
  expect(api.mock.calls.some(([path]) => /answer-|conversation-operational-reads/.test(path))).toBe(false)
})

it('uncertain planning preserves its request for explicit reconciliation rather than dispatching an answer', async () => {
  const submission = { requestId: 'original-plan-request', bundleId: 'bundle', provider: 'CODEX', requestedModel: 'fixture-model', digest: 'plan-digest', approved: true as const }
  vi.mocked(sendConversationPlan).mockImplementationOnce(async (_workspace, _bundle, _provider, _model, _signal, remember) => {
    remember(submission)
    throw new Error('접수 불확실')
  }).mockResolvedValueOnce({ ...planBinding, id: 'plan', workspaceId: 'one', bundleId: 'bundle', status: 'SUCCEEDED', decision: 'PROPOSE_MYSQL_READ' })
  api.mockResolvedValueOnce(bundle)
  render(<ChatPanel {...props} />)
  await userEvent.type(screen.getByRole('textbox'), '가상 업무 오류{Enter}')
  expect(await screen.findByRole('alert')).toHaveTextContent('접수 불확실')
  expect(screen.queryByRole('button', { name: '질문 수정하기' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '답변 받기' })).not.toBeInTheDocument()
  expect(sendConversationPlan).toHaveBeenCalledTimes(1)
  fireEvent.click(screen.getByRole('button', { name: '조사 접수 상태 확인' }))
  await screen.findByText('운영 조회 제안 · 실행하지 않음')
  expect(vi.mocked(sendConversationPlan).mock.calls[1][6]).toEqual(submission)
  expect(api).toHaveBeenCalledTimes(1)
})

it('reopening a conversation does not plan or generate an answer', async () => {
  api.mockResolvedValueOnce(thread)
  render(<ChatPanel {...props} selectedId="thread" />)
  await screen.findByText('가상 답변 [R1]')
  expect(sendConversationPlan).not.toHaveBeenCalled()
  expect(api).toHaveBeenCalledTimes(1)
})

it('restores a pending plan on reopen without answer fallback or another AI dispatch', async () => {
  vi.mocked(readConversationPlan).mockResolvedValueOnce({ ...planBinding, id: 'plan', workspaceId: 'one', bundleId: 'bundle', status: 'RUNNING' })
  api.mockResolvedValueOnce({ ...thread, jobs: [] })
  render(<ChatPanel {...props} selectedId="thread" />)
  await screen.findByText('추가 자료를 찾고 있어요.')
  expect(screen.queryByRole('button', { name: '답변 받기' })).not.toBeInTheDocument()
  expect(screen.getByRole('textbox')).toBeDisabled()
  expect(sendConversationPlan).not.toHaveBeenCalled()
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
})

it('cancelling a restored running plan releases the composer without generating an answer', async () => {
  const running = { ...planBinding, id: 'plan', workspaceId: 'one', bundleId: 'bundle', status: 'RUNNING' }
  vi.mocked(readConversationPlan).mockResolvedValueOnce(running)
  api.mockResolvedValueOnce({ ...thread, jobs: [] }).mockResolvedValueOnce({ ...running, status: 'CANCELLED' })
  render(<ChatPanel {...props} selectedId="thread" />)
  fireEvent.click(await screen.findByRole('button', { name: '취소' }))
  await waitFor(() => expect(screen.getByRole('textbox')).toBeEnabled())
  await userEvent.type(screen.getByRole('textbox'), '수정한 질문')
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeEnabled()
  expect(screen.queryByRole('button', { name: '답변 받기' })).not.toBeInTheDocument()
  expect(sendConversationPlan).not.toHaveBeenCalled()
  expect(api.mock.calls.filter(([, options]) => options?.method === 'POST').map(([path]) => path)).toEqual(['/workspaces/one/investigation-plans/jobs/plan/cancel'])
})

it.each([400, 409, 413])('permits explicit rephrasing after definitive preview rejection %s without discarding the prior turn', async status => {
  vi.mocked(sendConversationPlan).mockRejectedValueOnce(new ApiError('계획 미리보기 거부', status))
  api.mockResolvedValueOnce(bundle).mockResolvedValueOnce({ ...bundle, id: 'rephrased', payload: { ...bundle.payload, question: '짧게 수정한 질문', status: 'NO_EVIDENCE', items: [] } })
  render(<ChatPanel {...props} />)
  await userEvent.type(screen.getByRole('textbox'), bundle.payload.question + '{Enter}')
  expect(await screen.findByRole('alert')).toHaveTextContent('계획 미리보기 거부')
  expect(screen.queryByRole('button', { name: '답변 받기' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '질문 수정하기' }))
  expect(screen.getByRole('textbox')).toHaveValue(bundle.payload.question)
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeEnabled()
  await userEvent.clear(screen.getByRole('textbox'))
  await userEvent.type(screen.getByRole('textbox'), '짧게 수정한 질문{Enter}')
  await waitFor(() => expect(sendConversationPlan).toHaveBeenCalledTimes(2))
  expect(screen.getByRole('heading', { name: bundle.payload.question })).toBeVisible()
  expect(JSON.parse(api.mock.calls[1][1]!.body as string)).toMatchObject({ threadId: 'thread', parentBundleId: 'bundle', question: '짧게 수정한 질문' })
  expect(api.mock.calls.some(([path]) => path.includes('/answer-'))).toBe(false)
})

it('restores uncertain plan approval after reload without dispatch until explicit same-ID reconciliation', async () => {
  const submission = { requestId: 'saved-plan', bundleId: 'bundle', ...planBinding, approved: true as const }
  savePlanSubmission({ workspaceId: 'one', bundleId: bundle.id, bundleDigest: bundle.digest }, submission)
  api.mockResolvedValueOnce({ ...thread, jobs: [] })
  vi.mocked(sendConversationPlan).mockResolvedValueOnce({ ...planBinding, id: 'saved-plan', workspaceId: 'one', bundleId: 'bundle', status: 'SUCCEEDED', decision: 'PROPOSE_MYSQL_READ' })
  render(<ChatPanel {...props} selectedId="thread" />)
  await screen.findByRole('button', { name: '조사 접수 상태 확인' })
  expect(sendConversationPlan).not.toHaveBeenCalled()
  expect(screen.queryByRole('button', { name: '답변 받기' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '조사 접수 상태 확인' }))
  await screen.findByText('운영 조회 제안 · 실행하지 않음')
  expect(vi.mocked(sendConversationPlan).mock.calls[0][6]).toEqual(submission)
  expect(api).toHaveBeenCalledTimes(1)
})

it('retries failed history plan reconciliation by GET only and never falls back to answer dispatch', async () => {
  vi.mocked(readConversationPlan).mockRejectedValueOnce(new Error('상태 확인 실패')).mockResolvedValueOnce({ ...planBinding, id: 'plan', workspaceId: 'one', bundleId: 'bundle', status: 'SUCCEEDED', decision: 'PROPOSE_MYSQL_READ' })
  api.mockResolvedValueOnce({ ...thread, jobs: [] })
  render(<ChatPanel {...props} selectedId="thread" />)
  expect(await screen.findByRole('alert')).toHaveTextContent('상태 확인 실패')
  expect(screen.queryByRole('button', { name: '답변 받기' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '조사 접수 상태 확인' }))
  await screen.findByText('운영 조회 제안 · 실행하지 않음')
  expect(readConversationPlan).toHaveBeenCalledTimes(2)
  expect(sendConversationPlan).not.toHaveBeenCalled()
  expect(api).toHaveBeenCalledTimes(1)
})

it('takes business clarification in the same composer and replans a new turn bound to the original issue', async () => {
  const proposal = { ...planBinding, id: 'plan', workspaceId: 'one', bundleId: 'bundle', status: 'SUCCEEDED', decision: 'PROPOSE_MYSQL_READ', toolStatus: 'PENDING_SCOPE' }
  const scope = { jobId: 'plan', scopeId: 'scope', revision: 1, workspaceId: 'one', threadId: 'thread', parentBundleId: 'bundle', tool: 'MYSQL_READ', expiresAt: '2099-01-01T00:00:00Z', sessionOnly: true, executionAllowed: false, internalResolutionRequired: true, questions: [{ topic: 'ENVIRONMENT', prompt: '어느 환경에서 발생한 현상인지 알려 주세요.' }] }
  const followup = { ...bundle, id: 'clarified', payload: { ...bundle.payload, question: '운영 환경, 오늘 오전 10시 한국 시간', items: [], status: 'NO_EVIDENCE' } }
  vi.mocked(readConversationPlan).mockResolvedValueOnce(proposal)
  api.mockImplementation(async path => {
    if (path.endsWith('/chat-threads/thread')) return { ...thread, jobs: [] }
    if (path.endsWith('/clarification')) return scope
    if (path.endsWith('/chat-threads')) return followup
    throw new Error('Unexpected request: ' + path)
  })
  render(<ChatPanel {...props} selectedId="thread" />)
  await screen.findByText(scope.questions[0].prompt)
  expect(screen.queryByRole('button', { name: '답변 받기' })).not.toBeInTheDocument()
  expect(screen.getAllByRole('textbox')).toHaveLength(1)
  await userEvent.type(screen.getByRole('textbox'), followup.payload.question + '{Enter}')
  await waitFor(() => expect(sendConversationPlan).toHaveBeenCalledTimes(1))
  expect(vi.mocked(sendConversationPlan).mock.calls[0].slice(0, 4)).toEqual(['one', 'clarified', 'CODEX', 'fixture-model'])
  const turn = api.mock.calls.find(([path]) => path.endsWith('/chat-threads'))!
  expect(JSON.parse(turn[1]!.body as string)).toMatchObject({ threadId: 'thread', parentBundleId: 'bundle', question: followup.payload.question })
  expect(api.mock.calls.filter(([path]) => path.endsWith('/clarification'))).toHaveLength(2)
  expect(api.mock.calls.some(([path]) => /answer-|\/execute/.test(path))).toBe(false)
})

it('releases the chat lock when capture handoff is aborted despite an abort-ignoring history request', async () => {
  const controller = new AbortController()
  const scope = { jobId: 'plan', scopeId: 'scope', revision: 1, workspaceId: 'one', threadId: 'thread', parentBundleId: 'bundle', tool: 'MYSQL_READ', expiresAt: '2099-01-01T00:00:00Z', sessionOnly: true, executionAllowed: false, internalResolutionRequired: false, questions: [] }
  const receipt = { id: 'read', threadId: 'thread', parentBundleId: 'bundle', status: 'CAPTURED', attachment: { id: 'captured-attachment' }, contentHash: 'hash', externalCalls: 0 }
  vi.mocked(readConversationPlan).mockResolvedValueOnce({ ...planBinding, id: 'plan', workspaceId: 'one', bundleId: 'bundle', status: 'SUCCEEDED', decision: 'PROPOSE_MYSQL_READ', toolStatus: 'PENDING_SCOPE' })
  vi.spyOn(ConversationReadModule, 'default').mockImplementation(({ onCaptured, onBusyChange }) => <button onClick={() => {
    onBusyChange(true)
    void onCaptured(receipt, controller.signal).finally(() => onBusyChange(false))
  }}>가상 자료 연결</button>)
  let reads = 0
  let resolve!: (value: unknown) => void
  api.mockImplementation(async path => {
    if (path.endsWith('/clarification')) return scope
    if (path.endsWith('/chat-threads/thread')) {
      if (++reads === 1) return thread
      return new Promise(done => { resolve = done })
    }
    throw new Error('Unexpected request: ' + path)
  })
  render(<ChatPanel {...props} selectedId="thread" />)
  fireEvent.click(await screen.findByRole('button', { name: '가상 자료 연결' }))
  expect(screen.getByRole('textbox')).toBeDisabled()
  await act(async () => controller.abort())
  await waitFor(() => expect(screen.getByRole('textbox')).toBeEnabled())
  await act(async () => resolve(thread))
  expect(api.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false)
})

it.each(['unanswered', 'answered', 'tampered'])('reconciles a committed operational turn with a lost response: %s', async scenario => {
  const scope = { jobId: 'plan', scopeId: 'scope', revision: 1, workspaceId: 'one', threadId: 'thread', parentBundleId: 'bundle', tool: 'MYSQL_READ', expiresAt: '2099-01-01T00:00:00Z', sessionOnly: true, executionAllowed: false, internalResolutionRequired: false, questions: [] }
  const receipt = { id: 'read', threadId: 'thread', parentBundleId: 'bundle', status: 'CAPTURED', attachment: { id: 'captured-attachment' }, contentHash: 'capture-hash', externalCalls: 0 }
  vi.mocked(readConversationPlan).mockResolvedValueOnce({ ...planBinding, id: 'plan', workspaceId: 'one', bundleId: 'bundle', status: 'SUCCEEDED', decision: 'PROPOSE_MYSQL_READ', toolStatus: 'PENDING_SCOPE' })
  vi.spyOn(ConversationReadModule, 'default').mockImplementation(({ onCaptured }) => <button onClick={() => { void onCaptured(receipt, new AbortController().signal).catch(() => {}) }}>저장한 가상 자료로 답변</button>)
  let accepted: typeof bundle | null = null
  let acceptedRequest = ''
  const answer = { ...completed, id: 'recovered-answer', workspaceId: 'one', bundleId: 'recovered-bundle', answer: '복구한 답변' }
  api.mockImplementation(async (path, options) => {
    if (path.endsWith('/clarification')) return scope
    if (path.endsWith('/chat-threads/thread')) return accepted ? { id: 'thread', bundles: [bundle, accepted], jobs: scenario === 'answered' ? [answer] : [] } : thread
    if (path.endsWith('/chat-threads')) {
      const input = JSON.parse(options!.body as string)
      acceptedRequest = input.requestId
      const saved = { ...bundle, id: 'recovered-bundle', payload: { ...bundle.payload,
        chatContext: { threadId: 'thread', parentBundleId: 'bundle', requestId: input.requestId },
        items: [{ ...bundle.payload.items[0], kind: 'ATTACHMENT_TEXT', entryId: receipt.attachment.id, contentHash: scenario === 'tampered' ? 'changed' : receipt.contentHash }] } }
      accepted = saved
      throw new Error('대화 저장 응답 유실')
    }
    if (path.endsWith('/answer-preview')) return { ...prepared, bundleId: 'recovered-bundle' }
    if (path.endsWith('/answer-approval')) return options?.method === 'POST' ? { status: 'APPROVED', approval: { ...approved, bundleId: 'recovered-bundle' } } : { status: 'NONE', approval: null }
    if (path.endsWith('/answer-jobs')) return answer
    throw new Error('Unexpected request: ' + path)
  })
  render(<ChatPanel {...props} selectedId="thread" />)
  fireEvent.click(await screen.findByRole('button', { name: '저장한 가상 자료로 답변' }))
  await screen.findByText('대화 저장 응답 유실')
  await waitFor(() => expect(screen.getByRole('textbox')).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '저장한 가상 자료로 답변' }))
  if (scenario === 'tampered') expect(await screen.findByRole('alert')).toHaveTextContent('저장된 대화의 요청 또는 자료가 다릅니다')
  else await screen.findByText('복구한 답변')
  const posts = api.mock.calls.filter(([path, options]) => path.endsWith('/chat-threads') && options?.method === 'POST')
  expect(posts).toHaveLength(1)
  expect(JSON.parse(posts[0][1]!.body as string).requestId).toBe(acceptedRequest)
  expect(api.mock.calls.filter(([path]) => path.endsWith('/answer-jobs'))).toHaveLength(scenario === 'unanswered' ? 1 : 0)
  expect(api.mock.calls.some(([path]) => /\/execute|\/capture/.test(path))).toBe(false)
})

it('keeps the original turn identity when an automatic search capture resolves in a React batch', async () => {
  const captured = { ...bundle, id: 'captured', digest: 'captured-digest', payload: { ...bundle.payload,
    chatContext: { threadId: 'thread', parentBundleId: 'bundle', extractive: { turnKind: 'SEARCH_CAPTURE' } } } }
  let resolveCapture!: (value: typeof captured) => void
  const capture = new Promise<typeof captured>(resolve => { resolveCapture = resolve })
  vi.mocked(sendConversationPlan).mockResolvedValue({ ...planBinding, id: 'plan', workspaceId: 'one', bundleId: 'bundle', status: 'SUCCEEDED', decision: 'SEARCH', toolStatus: 'OK', truncated: false })
  api.mockImplementation(async (path, options) => {
    if (path.endsWith('/chat-threads')) return bundle
    if (path.endsWith('/plan/capture')) return capture
    if (path.endsWith('/captured/answer-preview')) return { ...prepared, bundleId: 'captured', bundleDigest: 'captured-digest' }
    if (path.endsWith('/captured/answer-approval')) return options?.method === 'POST'
      ? { status: 'APPROVED', approval: { ...approved, bundleId: 'captured' } } : { status: 'NONE', approval: null }
    if (path.endsWith('/captured/answer-jobs')) return { ...completed, bundleId: 'captured' }
    throw new Error(`Unexpected request: ${path}`)
  })
  function Parent() {
    const [selectedId, setSelectedId] = useState<string | null>(null)
    return <ChatPanel {...props} selectedId={selectedId} onCreated={setSelectedId} />
  }
  const view = render(<StrictMode><Parent /></StrictMode>)
  await userEvent.type(screen.getByRole('textbox'), `${bundle.payload.question}{Enter}`)
  await waitFor(() => expect(api.mock.calls.some(([path]) => path.endsWith('/plan/capture'))).toBe(true))
  await act(async () => { resolveCapture(captured); await capture })
  await screen.findByText('가상 답변 [R1]')
  expect(screen.getAllByRole('heading', { name: bundle.payload.question })).toHaveLength(1)
  expect(screen.getAllByText('추가로 확인한 자료')).toHaveLength(1)
  const turns = view.container.querySelectorAll('.chat-thread')
  expect(turns).toHaveLength(2)
  expect(turns[0].querySelector('.question-bubble')).toHaveTextContent(bundle.payload.question)
  expect(turns[0].querySelector('button')).toBeNull()
  expect(screen.queryByRole('button', { name: '답변 받기' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '브라우저 화면 조사' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '찾은 자료로 조회 범위 확인' })).not.toBeInTheDocument()
  expect(sendConversationPlan).toHaveBeenCalledTimes(1)
  expect(api.mock.calls.filter(([path]) => path.endsWith('/answer-jobs'))).toHaveLength(1)
  expect(captured.payload.chatContext.parentBundleId).toBe(bundle.id)
})

it('renders a search capture as materials rather than a duplicate user question and never plans on reopen', async () => {
  const captured = { ...bundle, id: 'captured', payload: { ...bundle.payload, chatContext: { threadId: 'thread', parentBundleId: 'bundle', extractive: { turnKind: 'SEARCH_CAPTURE' } } } }
  const followup = { ...bundle, id: 'followup', payload: { ...bundle.payload, question: '가상 후속 질문',
    chatContext: { threadId: 'thread', parentBundleId: 'captured', extractive: { turnKind: 'USER_QUESTION' } } } }
  const secondCapture = { ...followup, id: 'second-capture', payload: { ...followup.payload,
    chatContext: { threadId: 'thread', parentBundleId: 'followup', extractive: { turnKind: 'SEARCH_CAPTURE' } } } }
  api.mockResolvedValueOnce({ id: 'thread', bundles: [bundle, captured, followup, secondCapture], jobs: [
    { ...completed, workspaceId: 'one', bundleId: 'captured' },
    { ...completed, id: 'second-answer', workspaceId: 'one', bundleId: 'second-capture', answer: '가상 후속 답변' },
  ] })
  const view = render(<ChatPanel {...props} selectedId="thread" />)
  await screen.findByText('가상 후속 답변')
  expect(screen.getByText('가상 답변 [R1]')).toBeVisible()
  expect(screen.getAllByText('추가로 확인한 자료')).toHaveLength(2)
  expect(view.container.querySelectorAll('.question-bubble')).toHaveLength(2)
  expect(view.container.querySelectorAll('.chat-thread')).toHaveLength(4)
  expect(screen.getAllByRole('heading', { name: bundle.payload.question })).toHaveLength(1)
  expect(screen.getAllByRole('heading', { name: followup.payload.question })).toHaveLength(1)
  expect(screen.queryByRole('button', { name: '브라우저 화면 조사' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '찾은 자료로 조회 범위 확인' })).not.toBeInTheDocument()
  expect(sendConversationPlan).not.toHaveBeenCalled()
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
})

it('ready scope approval captures bounded evidence and answers in the same thread without using the draft or its attachments', async () => {
  vi.mocked(readConversationPlan).mockResolvedValueOnce({ ...planBinding, id: 'plan', workspaceId: 'one', bundleId: 'bundle', status: 'SUCCEEDED', decision: 'PROPOSE_MYSQL_READ', toolStatus: 'PENDING_SCOPE', output: 'scope' })
  const scope = { jobId: 'plan', scopeId: 'scope', revision: 1, workspaceId: 'one', threadId: 'thread', parentBundleId: 'bundle', tool: 'MYSQL_READ', expiresAt: '2099-01-01T00:00:00Z', sessionOnly: true, executionAllowed: false, internalResolutionRequired: false, questions: [] }
  const read = { id: 'read', workspaceId: 'one', threadId: 'thread', parentBundleId: 'bundle', tool: 'MYSQL_READ', sessionOnly: true, scopeId: 'scope', scopeRevision: 1, digest: 'read-digest', queryPreview: 'synthetic scope', expiresAt: scope.expiresAt, maxRows: 10, maxBytes: 4096, timeoutSeconds: 10 }
  const next = { ...bundle, id: 'operational-bundle', digest: 'operational-digest', payload: { ...bundle.payload, items: [{ ...bundle.payload.items[0], kind: 'ATTACHMENT_TEXT' }] } }
  api.mockImplementation(async (path, options) => {
    if (path.endsWith('/chat-threads/thread')) return { id: thread.id, bundles: thread.bundles, jobs: thread.jobs }
    if (path.includes('/investigation-plans/jobs?')) return [{ id: 'plan', workspaceId: 'one', bundleId: 'bundle', status: 'SUCCEEDED', decision: 'PROPOSE_MYSQL_READ', toolStatus: 'PENDING_SCOPE', output: 'scope' }]
    if (path.endsWith('/clarification')) return scope
    if (path.endsWith('/conversation-operational-reads/preview')) return read
    if (path.endsWith('/execute')) return { ...read, status: 'SUCCEEDED', output: 'result', rows: 1, truncated: false }
    if (path.endsWith('/capture-preview')) return { ...read, digest: 'capture-digest', text: 'bounded synthetic evidence', bytes: 26, truncated: false }
    if (path.endsWith('/read/capture')) return { id: 'read', threadId: 'thread', parentBundleId: 'bundle', status: 'CAPTURED', attachment: { id: 'captured-attachment' }, contentHash: 'hash', externalCalls: 0 }
    if (path.endsWith('/chat-threads')) return next
    if (path.endsWith('/answer-preview')) return { ...prepared, bundleId: next.id, bundleDigest: next.digest }
    if (path.endsWith('/answer-approval')) return options?.method === 'POST' ? { status: 'APPROVED', approval: { ...approved, bundleId: next.id } } : { status: 'NONE', approval: null }
    if (path.endsWith('/answer-jobs')) return { ...completed, bundleId: next.id, answer: '조회 근거 답변 [R1]' }
    throw new Error('Unexpected request: ' + path)
  })
  render(<ChatPanel {...props} selectedId="thread" />)
  fireEvent.click(await screen.findByRole('button', { name: '조회 범위 확인' }))
  expect(api.mock.calls.some(([path]) => path.endsWith('/execute'))).toBe(false)
  await waitFor(() => expect(screen.getByRole('textbox')).toBeEnabled())
  await userEvent.type(screen.getByRole('textbox'), '나중에 보낼 질문')
  fireEvent.click(await screen.findByRole('button', { name: '이 범위만 읽기' }))
  fireEvent.click(await screen.findByRole('button', { name: '이 자료로 답변 받기' }))
  expect(await screen.findByText('조회 근거 답변 [R1]')).toBeVisible()
  expect(screen.getByText('가상 답변 [R1]')).toBeVisible()
  expect(screen.getByRole('textbox')).toHaveValue('나중에 보낼 질문')
  const turn = api.mock.calls.find(([path]) => path.endsWith('/chat-threads'))!
  expect(JSON.parse(turn[1]!.body as string)).toEqual({ requestId: expect.any(String), threadId: 'thread', parentBundleId: 'bundle', question: bundle.payload.question, attachmentIds: ['captured-attachment'] })
  expect(api.mock.calls.filter(([path]) => path.endsWith('/execute'))).toHaveLength(1)
  expect(sendConversationPlan).not.toHaveBeenCalled()
})

it.each([
  ['questions', { omittedQuestions: 1 }], ['answers', { omittedAnswers: 1 }], ['truncated', { truncatedAnswerBundleIds: ['previous'] }],
])('새 질문과 복원 이력의 %s 생략 표시가 같고 추가 요청하지 않는다', async (_reason, omission) => {
  const saved = contextBundle({ ...completeContext, ...omission })
  api.mockResolvedValueOnce(saved)
  const view = render(<ChatPanel {...props} />)
  expect(screen.queryByText('이전 대화 일부만 참고')).not.toBeInTheDocument()
  await userEvent.type(screen.getByRole('textbox'), '가상 질문{Enter}')
  expect(await screen.findByText('이전 대화 일부만 참고')).toBeVisible()
  expect(screen.getByText('이전 대화 일부만 참고').closest('.chat-thread')).toHaveTextContent('가상 질문')
  expect(api).toHaveBeenCalledTimes(1)
  expect(api).toHaveBeenLastCalledWith('/workspaces/one/chat-threads', expect.objectContaining({ method: 'POST' }))
  view.unmount(); api.mockClear()
  api.mockResolvedValueOnce({ ...thread, bundles: [saved], jobs: [] })
  render(<ChatPanel {...props} selectedId="thread" />)
  expect(await screen.findByText('이전 대화 일부만 참고')).toBeVisible()
  expect(api).toHaveBeenCalledExactlyOnceWith('/workspaces/one/chat-threads/thread', expect.objectContaining({ signal: expect.any(AbortSignal) }))
  expect(api.mock.calls[0][1]?.method).toBeUndefined()
})

it.each([
  undefined, null, completeContext, {}, [],
  { ...completeContext, version: 2, omittedQuestions: 1 },
  { ...completeContext, omittedQuestions: '1' },
  { ...completeContext, omittedQuestions: -1 },
  { ...completeContext, omittedQuestions: 3 },
  { ...completeContext, omittedAnswers: 0.5 },
  { ...completeContext, omittedAnswers: 1, maxSerializedCharacters: 0 },
  { ...completeContext, omittedAnswers: 1, answerCharacterLimit: null },
  { ...completeContext, truncatedAnswerBundleIds: [null] },
  { ...completeContext, truncatedAnswerBundleIds: 'previous' },
  { ...completeContext, omittedAnswers: 1, questions: [null] },
  { ...completeContext, omittedAnswers: 1, questions: [{ bundleId: 'previous', bundleDigest: 'bad', question: '질문' }] },
  { ...completeContext, omittedAnswers: 1, retrievalQueries: [false] },
])('생략 없는 문맥·구형 이력·잘못된 메타데이터는 안내를 추측하지 않는다: %j', async extractive => {
  api.mockResolvedValueOnce({ ...thread, bundles: [contextBundle(extractive)], jobs: [] })
  render(<ChatPanel {...props} selectedId="thread" />)
  await screen.findByRole('heading', { name: '가상 질문' })
  expect(screen.queryByText('이전 대화 일부만 참고')).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(1)
})

it('각 턴의 생략 표시는 해당 묶음에만 붙고 다른 대화로 전환하면 사라진다', async () => {
  const first = contextBundle({ ...completeContext, omittedAnswers: 1 })
  const second = { ...contextBundle(completeContext), id: 'second', payload: { ...contextBundle(completeContext).payload, question: '두 번째 질문' } }
  api.mockResolvedValueOnce({ ...thread, bundles: [first, second], jobs: [] })
    .mockResolvedValueOnce({ ...thread, id: 'other', bundles: [contextBundle(null)], jobs: [] })
  const view = render(<ChatPanel {...props} selectedId="thread" />)
  expect(await screen.findByText('이전 대화 일부만 참고')).toBeVisible()
  expect(screen.getAllByText('이전 대화 일부만 참고')).toHaveLength(1)
  expect(screen.getByText('이전 대화 일부만 참고').closest('.chat-thread')).not.toHaveTextContent('두 번째 질문')
  view.rerender(<ChatPanel {...props} selectedId="other" />)
  await screen.findByRole('heading', { name: '가상 질문' })
  expect(screen.queryByText('이전 대화 일부만 참고')).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(2)
  expect(api.mock.calls.every(([, options]) => !options?.method || options.method === 'GET')).toBe(true)
})

it.each(['PROPOSE_MYSQL_READ'] as const)('계획 %s 결과는 양식이나 자동 조회를 열지 않는다', async decision => {
  vi.mocked(readConversationPlan).mockResolvedValueOnce({ ...planBinding, id: 'plan', workspaceId: 'one', bundleId: 'bundle', status: 'SUCCEEDED', decision })
  api.mockImplementation(async path => {
    if (path.endsWith('/chat-threads/thread')) return thread
    if (path.includes('/investigation-plans/jobs?')) return [{ id: 'plan', workspaceId: 'one', bundleId: 'bundle', status: 'SUCCEEDED', decision }]
    throw new Error('Unexpected request: ' + path)
  })
  render(<ChatPanel {...props} selectedId="thread" />)
  expect(await screen.findByText('운영 조회 제안 · 실행하지 않음')).toBeVisible()
  for (const name of ['운영 자료 조회', '조회 양식 열기', 'DB 데이터 조회', '이 자료로 다시 답변']) expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '접기' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '자료 더 찾아보기' })).not.toBeInTheDocument()
  expect(api.mock.calls.map(([path]) => path)).toEqual(['/workspaces/one/chat-threads/thread'])
})

it('복원한 추가 검색 근거는 새 대화 턴으로 고정하고 별도 답변 승인으로 전송한다', async () => {
  vi.mocked(readConversationPlan).mockResolvedValueOnce({ ...planBinding, id: 'plan-job', workspaceId: 'one', bundleId: 'bundle', status: 'SUCCEEDED', decision: 'SEARCH', toolStatus: 'OK', query: 'Strategy', output: '추가 자료 발췌' })
  const captured = { ...bundle, id: 'captured', digest: 'captured-digest', payload: { ...bundle.payload, items: [{ ...bundle.payload.items[0], title: '추가 검색 자료' }] } }
  api.mockImplementation(async (path, options) => {
    if (path.endsWith('/chat-threads/thread')) return thread
    if (path.includes('/investigation-plans/jobs?')) return []
    if (path.endsWith('/investigation-plans/preview')) return { digest: 'plan-digest', prompt: '고정한 검색 계획', provider: 'CODEX', requestedModel: 'fixture-model', characters: 10, maxCalls: 1 }
    if (path.endsWith('/investigation-plans/jobs')) return { id: 'plan-job', workspaceId: 'one', bundleId: 'bundle', status: 'SUCCEEDED', decision: 'SEARCH', toolStatus: 'OK', query: 'Strategy', output: '추가 자료 발췌' }
    if (path.endsWith('/plan-job/capture')) return captured
    if (path.endsWith('/answer-preview')) return { ...prepared, bundleId: 'captured', bundleDigest: 'captured-digest', digest: 'new-answer-digest' }
    if (path.endsWith('/answer-approval')) return options?.method === 'POST'
      ? { status: 'APPROVED', approval: { ...approved, id: 'new-approval', bundleId: 'captured', digest: 'new-answer-digest' } }
      : { status: 'NONE', approval: null }
    return { ...completed, id: 'new-answer', approvalId: 'new-approval', bundleId: 'captured', answer: '추가 자료로 답변 [R1]' }
  })
  render(<ChatPanel {...props} selectedId="thread" />)
  fireEvent.click(await screen.findByRole('button', { name: '이 자료로 다시 답변' }))
  expect(await screen.findByText('추가 자료로 답변 [R1]')).toBeVisible()
  expect(screen.getByText('가상 답변 [R1]')).toBeVisible()
  expect(screen.getByText('[R1] 추가 검색 자료')).toBeVisible()
  const answerRequests = api.mock.calls.filter(([path, options]) => path.endsWith('/answer-jobs') && options?.method === 'POST')
  expect(answerRequests).toHaveLength(1)
  expect(answerRequests[0][0]).toContain('/captured/')
  expect(JSON.parse(answerRequests[0][1]!.body as string)).toMatchObject({ approvalId: 'new-approval', digest: 'new-answer-digest' })
})

it('검색 준비 중에는 질문을 막고 준비 완료 후 입력을 연다', async () => {
  const view = render(<ChatPanel {...props} searchState="INDEXING" />)
  expect(screen.getByRole('textbox', { name: '질문' })).toBeDisabled()
  expect(screen.getByRole('status')).toHaveTextContent('자료 검색을 준비하고 있어요')
  fireEvent.submit(document.querySelector('form')!)
  expect(api.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false)
  view.rerender(<ChatPanel {...props} searchState="READY" />)
  expect(screen.getByRole('textbox', { name: '질문' })).toBeEnabled()
  view.rerender(<ChatPanel {...props} searchState="ERROR" />)
  expect(screen.getByRole('textbox', { name: '질문' })).toBeDisabled()
  expect(screen.getByRole('status')).toHaveTextContent('앱을 다시 실행')
})

it('답변에서 인용하지 않은 후보와 코드블록 속 번호는 출처로 표시하지 않는다', async () => {
  const value = { ...bundle, payload: { ...bundle.payload, items: [bundle.payload.items[0],
    { ...bundle.payload.items[0], label: 'R2', title: '실제 인용 자료' }] } }
  api.mockResolvedValue({ ...thread, bundles: [value], jobs: [{ ...completed, workspaceId: 'one', answer: '확인한 내용입니다. [R2]\n```\n[R1]\n```' }] })
  render(<ChatPanel {...props} selectedId="thread" />)
  expect(await screen.findByText('[R2] 실제 인용 자료')).toBeVisible()
  expect(screen.queryByText('[R1] 가상 자료')).not.toBeInTheDocument()
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
})

it('연속 질문은 이전 질문과 답변을 유지한 채 아래에 추가하고 각각 저장한다', async () => {
  let count = 0
  api.mockImplementation(async (path, options) => {
    if (path.endsWith('/chat-threads')) {
      count++
      return { ...bundle, id: `bundle-${count}`, payload: { ...bundle.payload, question: JSON.parse(options!.body as string).question } }
    }
    const bundleId = `bundle-${count}`
    if (path.endsWith('/answer-preview')) return { ...prepared, bundleId }
    if (path.endsWith('/answer-approval')) return options?.method === 'POST'
      ? { status: 'APPROVED', approval: { ...approved, bundleId } } : { status: 'NONE', approval: null }
    return { ...completed, bundleId, id: `job-${count}`, answer: `답변 ${count} [R1]` }
  })
  function Conversation() {
    const [selectedId, setSelectedId] = useState<string | null>(null)
    return <ChatPanel {...props} selectedId={selectedId} onCreated={setSelectedId} />
  }
  render(<Conversation />)
  await userEvent.type(screen.getByRole('textbox'), '첫 질문{Enter}')
  const firstAnswer = await screen.findByText('답변 1 [R1]')
  await waitFor(() => expect(screen.getByRole('textbox')).toBeEnabled())
  await userEvent.type(screen.getByRole('textbox'), '두 번째 질문{Enter}')
  const secondAnswer = await screen.findByText('답변 2 [R1]')
  expect(screen.getByRole('heading', { name: '첫 질문' })).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: '두 번째 질문' })).toBeInTheDocument()
  expect(screen.getByText('답변 1 [R1]')).toBe(firstAnswer)
  expect(firstAnswer.compareDocumentPosition(secondAnswer) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(screen.getAllByRole('region', { name: '참고 자료' })).toHaveLength(2)
  expect(count).toBe(2)
  const requests = api.mock.calls.filter(([path]) => path === '/workspaces/one/chat-threads')
    .map(([, options]) => JSON.parse(options!.body as string))
  expect(requests).toEqual([
    { requestId: expect.any(String), threadId: null, parentBundleId: null, question: '첫 질문' },
    { requestId: expect.any(String), threadId: 'thread', parentBundleId: 'bundle-1', question: '두 번째 질문' },
  ])
  expect(requests[0].requestId).toMatch(/^[0-9a-f-]{36}$/)
  expect(requests[1].requestId).toMatch(/^[0-9a-f-]{36}$/)
  expect(requests[1].requestId).not.toBe(requests[0].requestId)
  expect(api.mock.calls.filter(([path]) => path.endsWith('/answer-jobs'))).toHaveLength(2)
  await waitFor(() => expect(screen.getByRole('textbox')).toBeEnabled())
})

it('근거 없는 질문 뒤에도 새 질문을 보내고 다른 이력 선택 시 대화를 섞지 않는다', async () => {
  let count = 0
  api.mockImplementation(async (path, options) => {
    if (path === '/workspaces/one/chat-threads/other') return { ...thread, id: 'other', jobs: [], bundles: [{ ...bundle, id: 'other', payload: { ...bundle.payload, question: '다른 이력', status: 'NO_EVIDENCE', items: [], chatContext: { threadId: 'other' } } }] }
    return { ...bundle, id: options?.method === 'POST' ? `empty-${++count}` : 'other',
      payload: { ...bundle.payload, question: options?.method === 'POST' ? JSON.parse(options.body as string).question : '다른 이력', status: 'NO_EVIDENCE', items: [] } }
  })
  const view = render(<ChatPanel {...props} />)
  await userEvent.type(screen.getByRole('textbox'), '첫 빈 질문{Enter}')
  await waitFor(() => expect(screen.getByRole('textbox')).toBeEnabled())
  await userEvent.type(screen.getByRole('textbox'), '두 번째 빈 질문{Enter}')
  await waitFor(() => expect(screen.getAllByText('질문과 관련된 내용을 찾지 못했어요.')).toHaveLength(2))
  view.rerender(<ChatPanel {...props} selectedId="other" />)
  await screen.findByRole('heading', { name: '다른 이력' })
  expect(screen.queryByRole('heading', { name: '첫 빈 질문' })).not.toBeInTheDocument()
  expect(screen.queryByRole('heading', { name: '두 번째 빈 질문' })).not.toBeInTheDocument()
})

it('여러 턴의 이력을 순서대로 다시 열고 마지막 질문에 이어 전송한다', async () => {
  const second = { ...bundle, id: 'bundle-two', payload: { ...bundle.payload, question: '두 번째 질문' } }
  const secondJob = { ...thread.jobs[0], id: 'job-two', bundleId: second.id, answer: '두 번째 답변 [R1]' }
  api.mockResolvedValueOnce({ ...thread, bundles: [bundle, second], jobs: [secondJob, thread.jobs[0]] })
    .mockResolvedValueOnce({ ...bundle, id: 'bundle-three', payload: { ...bundle.payload, question: '세 번째 질문', status: 'NO_EVIDENCE', items: [] } })
  render(<ChatPanel {...props} selectedId="thread" />)
  const firstAnswer = await screen.findByText('가상 답변 [R1]')
  const secondAnswer = screen.getByText('두 번째 답변 [R1]')
  expect(screen.getAllByRole('heading', { level: 2 }).map(heading => heading.textContent)).toEqual(['가상 질문', '두 번째 질문'])
  expect(firstAnswer.closest('.chat-thread')).toHaveTextContent('가상 질문')
  expect(secondAnswer.closest('.chat-thread')).toHaveTextContent('두 번째 질문')
  expect(firstAnswer.compareDocumentPosition(secondAnswer) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(screen.getAllByRole('region', { name: '참고 자료' })).toHaveLength(2)
  expect(api).toHaveBeenCalledExactlyOnceWith('/workspaces/one/chat-threads/thread', expect.objectContaining({ signal: expect.any(AbortSignal) }))
  expect(api.mock.calls[0][1]?.method).toBeUndefined()
  expect(props.onCreated).not.toHaveBeenCalled()
  await userEvent.type(screen.getByRole('textbox'), '세 번째 질문{Enter}')
  await screen.findByRole('heading', { name: '세 번째 질문' })
  expect(api).toHaveBeenCalledTimes(2)
  expect(api.mock.calls[1][0]).toBe('/workspaces/one/chat-threads')
  expect(api.mock.calls[1][1]?.method).toBe('POST')
  expect(JSON.parse(api.mock.calls[1][1]!.body as string)).toEqual({ requestId: expect.any(String), threadId: 'thread', parentBundleId: 'bundle-two', question: '세 번째 질문' })
  expect(screen.getByText('가상 답변 [R1]')).toBe(firstAnswer)
  expect(screen.getByText('두 번째 답변 [R1]')).toBe(secondAnswer)
  expect(props.onCreated).toHaveBeenCalledExactlyOnceWith('thread')
})

it.each([
  { status: 504, detail: 'LOCAL_SEARCH_TIMEOUT: 로컬 검색 시간 한도를 초과했습니다.' },
  { status: 503, detail: 'LOCAL_SEARCH_FAILED: 로컬 검색을 완료하지 못했습니다. 답변은 실행하지 않았습니다.' },
  { status: 429, detail: 'LOCAL_SEARCH_DENIED: 로컬 검색을 완료하지 못했습니다. 답변은 실행하지 않았습니다.' },
  { status: 409, detail: 'LOCAL_SEARCH_CANCELLED: 로컬 검색을 취소했습니다.' },
].flatMap(failure => [false, true].map(reopen => ({ ...failure, reopen }))))(
  'bounded retrieval $status releases the composer and retries the same request once (reopen: $reopen)',
  async ({ status, detail, reopen }) => {
    const attached = { id: 'retrieval-file', name: 'synthetic.txt', mediaType: 'text/plain', size: 9, characters: 9, truncated: false }
    let rejectFirst!: (reason: Error) => void
    let completeRetry!: (value: unknown) => void
    if (reopen) api.mockResolvedValueOnce(thread)
    api.mockResolvedValueOnce(attached)
      .mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectFirst = reject }))
      .mockImplementationOnce(() => new Promise(resolve => { completeRetry = resolve }))
    render(<ChatPanel {...props} selectedId={reopen ? 'thread' : null} />)
    const previousAnswer = reopen ? await screen.findByText('가상 답변 [R1]') : null
    await userEvent.upload(screen.getByLabelText('질문에 첨부할 파일'), new File(['synthetic'], attached.name, { type: attached.mediaType }))
    await screen.findByText(attached.name)
    const composer = screen.getByRole('textbox', { name: '질문' })
    await userEvent.type(composer, '가상 검색 재시도')
    const form = composer.closest('form')!
    const submissions = () => api.mock.calls.filter(([path, options]) => path === '/workspaces/one/chat-threads' && options?.method === 'POST')

    fireEvent.submit(form)
    fireEvent.submit(form)
    expect(submissions()).toHaveLength(1)
    expect(composer).toBeDisabled()
    expect(screen.getByRole('button', { name: '질문 보내기' })).toBeDisabled()
    await act(async () => rejectFirst(new ApiError(detail, status)))

    expect(await screen.findByRole('alert')).toHaveTextContent(detail)
    expect(composer).toBeEnabled()
    expect(composer).toHaveValue('가상 검색 재시도')
    expect(screen.getByRole('button', { name: '질문 보내기' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '파일 첨부' })).toBeEnabled()
    expect(screen.getByLabelText('질문에 첨부할 파일')).toBeEnabled()
    expect(screen.getByRole('button', { name: attached.name + ' 첨부 제거' })).toBeEnabled()
    expect(screen.queryByRole('heading', { name: '가상 검색 재시도' })).not.toBeInTheDocument()
    expect(props.onCreated).not.toHaveBeenCalled()
    if (previousAnswer) expect(previousAnswer).toBeVisible()
    await act(async () => {})
    expect(submissions()).toHaveLength(1)
    expect(api.mock.calls.map(([path]) => path)).toEqual([
      ...(reopen ? ['/workspaces/one/chat-threads/thread'] : []),
      '/workspaces/one/attachments', '/workspaces/one/chat-threads',
    ])
    expect(api.mock.calls.some(([path]) => /answer-preview|answer-approval|answer-jobs/.test(path))).toBe(false)

    await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
    fireEvent.submit(form)
    fireEvent.submit(form)
    expect(submissions()).toHaveLength(2)
    expect(composer).toBeDisabled()
    const original = JSON.parse(submissions()[0][1]!.body as string)
    expect(original).toEqual({ requestId: expect.any(String), threadId: reopen ? 'thread' : null,
      parentBundleId: reopen ? 'bundle' : null, question: '가상 검색 재시도', attachmentIds: [attached.id] })
    expect(original.requestId).toMatch(/^[0-9a-f-]{36}$/)
    expect(submissions()[1][1]!.body).toBe(submissions()[0][1]!.body)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    await act(async () => completeRetry({ ...bundle, id: 'retried-bundle', payload: {
      ...bundle.payload, question: '가상 검색 재시도', status: 'NO_EVIDENCE', items: [],
    } }))
    expect(await screen.findByRole('heading', { name: '가상 검색 재시도' })).toBeVisible()
    expect(composer).toBeEnabled()
    expect(composer).toHaveValue('')
    expect(screen.queryByRole('button', { name: attached.name + ' 첨부 제거' })).not.toBeInTheDocument()
    expect(props.onCreated).toHaveBeenCalledExactlyOnceWith('thread')
    if (previousAnswer) expect(previousAnswer).toBeVisible()
    expect(api.mock.calls.map(([path]) => path)).toEqual([
      ...(reopen ? ['/workspaces/one/chat-threads/thread'] : []),
      '/workspaces/one/attachments', '/workspaces/one/chat-threads', '/workspaces/one/chat-threads',
    ])
  },
)

it.each([false, true])('질문 저장 실패 시 입력·첨부·기존 대화를 보존하고 같은 부모로 재시도한다 (이력: %s)', async reopen => {
  const attached = { id: 'image', name: 'synthetic.png', mediaType: 'image/png', size: 8, characters: 8, truncated: false }
  if (reopen) api.mockResolvedValueOnce(thread)
  api.mockResolvedValueOnce(attached).mockRejectedValueOnce(new Error('질문 저장 실패'))
    .mockResolvedValueOnce({ ...bundle, id: 'next-bundle', payload: { ...bundle.payload, question: '다음 질문', status: 'NO_EVIDENCE', items: [] } })
  render(<ChatPanel {...props} selectedId={reopen ? 'thread' : null} />)
  const previousAnswer = reopen ? await screen.findByText('가상 답변 [R1]') : null
  await userEvent.upload(screen.getByLabelText('질문에 첨부할 파일'), new File(['synthetic'], 'synthetic.png', { type: 'image/png' }))
  await screen.findByText('synthetic.png')
  await userEvent.type(screen.getByRole('textbox'), '다음 질문{Enter}')
  expect(await screen.findByRole('alert')).toHaveTextContent('질문 저장 실패')
  expect(screen.getByRole('textbox')).toHaveValue('다음 질문')
  expect(screen.getByRole('textbox')).toBeEnabled()
  expect(screen.getByRole('button', { name: 'synthetic.png 첨부 제거' })).toBeEnabled()
  expect(screen.queryByRole('heading', { name: '다음 질문' })).not.toBeInTheDocument()
  expect(props.onCreated).not.toHaveBeenCalled()
  if (previousAnswer) expect(screen.getByText('가상 답변 [R1]')).toBe(previousAnswer)
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  await screen.findByRole('heading', { name: '다음 질문' })
  const submissions = api.mock.calls.filter(([path]) => path === '/workspaces/one/chat-threads')
  expect(submissions).toHaveLength(2)
  expect(JSON.parse(submissions[1][1]!.body as string).requestId).toBe(JSON.parse(submissions[0][1]!.body as string).requestId)
  for (const [, options] of submissions) {
    expect(options?.method).toBe('POST')
    expect(JSON.parse(options!.body as string)).toEqual({ requestId: expect.any(String), threadId: reopen ? 'thread' : null, parentBundleId: reopen ? 'bundle' : null, question: '다음 질문', attachmentIds: ['image'] })
  }
  expect(screen.getByRole('textbox')).toHaveValue('')
  expect(screen.queryByRole('button', { name: 'synthetic.png 첨부 제거' })).not.toBeInTheDocument()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  if (previousAnswer) {
    expect(screen.getByText('가상 답변 [R1]')).toBe(previousAnswer)
    expect(screen.getAllByRole('heading', { name: '가상 질문' })).toHaveLength(1)
  }
  expect(api).toHaveBeenCalledTimes(reopen ? 4 : 3)
  expect(props.onCreated).toHaveBeenCalledExactlyOnceWith('thread')
})

it.each(['question', 'attachment'])('실패 후 %s 변경은 새 요청 ID로 전송한다', async change => {
  api.mockResolvedValue(undefined).mockResolvedValueOnce(thread)
    .mockResolvedValueOnce({ id: 'image', name: 'synthetic.png', mediaType: 'image/png', size: 8, characters: 8, truncated: false })
    .mockRejectedValueOnce(new Error('질문 저장 실패'))
  render(<ChatPanel {...props} selectedId="thread" />)
  await screen.findByText('가상 답변 [R1]')
  await userEvent.upload(screen.getByLabelText('질문에 첨부할 파일'), new File(['synthetic'], 'synthetic.png', { type: 'image/png' }))
  await screen.findByText('synthetic.png')
  await userEvent.type(screen.getByRole('textbox'), '다음 질문{Enter}')
  await screen.findByRole('alert')
  if (change === 'question') await userEvent.type(screen.getByRole('textbox'), ' 수정')
  else {
    api.mockResolvedValueOnce(undefined)
    await userEvent.click(screen.getByRole('button', { name: 'synthetic.png 첨부 제거' }))
    await waitFor(() => expect(screen.queryByRole('button', { name: 'synthetic.png 첨부 제거' })).not.toBeInTheDocument())
  }
  api.mockRejectedValueOnce(new Error('다시 실패'))
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('다시 실패')
  const requests = api.mock.calls.filter(([path]) => path === '/workspaces/one/chat-threads')
    .map(([, options]) => JSON.parse(options!.body as string))
  expect(requests).toHaveLength(2)
  expect(requests[1]).toEqual({ requestId: expect.any(String), threadId: 'thread', parentBundleId: 'bundle', question: change === 'question' ? '다음 질문 수정' : '다음 질문', ...(change === 'question' ? { attachmentIds: ['image'] } : {}) })
  expect(requests[1].requestId).not.toBe(requests[0].requestId)
  expect(props.onCreated).not.toHaveBeenCalled()
})

it.each(['QUEUED', 'RUNNING', 'CANCELLING'])('%s 답변 중 강제 submit도 차단하고 완료 알림 후 같은 대화에 전송한다', async status => {
  api.mockResolvedValueOnce({ ...thread, jobs: [{ ...thread.jobs[0], status, answer: null }] })
    .mockResolvedValueOnce({ ...bundle, id: 'next-bundle', payload: { ...bundle.payload, question: '다음 질문', status: 'NO_EVIDENCE', items: [] } })
  render(<ChatPanel {...props} selectedId="thread" />)
  await screen.findByRole('heading', { name: '가상 질문' })
  const input = screen.getByRole('textbox')
  await userEvent.type(input, '다음 질문')
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeDisabled()
  fireEvent.submit(input.closest('form')!)
  expect(api).toHaveBeenCalledTimes(1)
  expect(props.onCreated).not.toHaveBeenCalled()
  const panel = vi.mocked(AnswerJobsPanel).mock.calls.at(-1)![0]
  expect(panel.onJobChange).toEqual(expect.any(Function))
  act(() => panel.onJobChange!(completed))
  expect(screen.getByText('가상 답변 [R1]')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeEnabled()
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  await screen.findByRole('heading', { name: '다음 질문' })
  expect(api).toHaveBeenCalledTimes(2)
  expect(api.mock.calls[1][0]).toBe('/workspaces/one/chat-threads')
  expect(JSON.parse(api.mock.calls[1][1]!.body as string)).toEqual({ requestId: expect.any(String), threadId: 'thread', parentBundleId: 'bundle', question: '다음 질문' })
  expect(screen.getByText('가상 답변 [R1]')).toBeInTheDocument()
})

it.each([
  '근거가 없거나 변경되었습니다.',
  'JSON을 포함한 요청이 20,000자를 초과합니다.',
  'ineligible',
])('접수 전 미리보기 실패는 명시적으로 새 대화에서 다시 질문할 수 있다: %s', async failure => {
  api.mockResolvedValueOnce(bundle)
  if (failure === 'ineligible') api.mockResolvedValueOnce({ ...prepared, validation: { eligible: false } })
  else api.mockRejectedValueOnce(new Error(failure))
  api.mockResolvedValueOnce({ ...thread, jobs: [] })
  api.mockResolvedValueOnce({ ...bundle, id: 'new-bundle', payload: { ...bundle.payload, question: '범위를 좁힌 질문', status: 'NO_EVIDENCE', items: [], chatContext: { threadId: 'new-thread' } } })
  render(<ChatPanel {...props} />)
  await userEvent.type(screen.getByRole('textbox'), '가상 질문{Enter}')
  expect(await screen.findByRole('alert')).toHaveTextContent(failure === 'ineligible' ? '자료가 변경되었습니다.' : failure)
  const input = screen.getByRole('textbox')
  await userEvent.type(input, '범위를 좁힌 질문')
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeDisabled()
  fireEvent.submit(input.closest('form')!)
  expect(api).toHaveBeenCalledTimes(2)
  expect(screen.getByRole('button', { name: '답변 받기' })).toBeEnabled()
  await userEvent.click(screen.getByRole('button', { name: '새 대화로 다시 질문' }))
  await waitFor(() => expect(screen.queryByRole('heading', { name: '가상 질문' })).not.toBeInTheDocument())
  expect(input).toHaveValue('범위를 좁힌 질문')
  expect(screen.queryByRole('heading', { name: '가상 질문' })).not.toBeInTheDocument()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(3)
  expect(api.mock.calls[2][0]).toBe('/workspaces/one/chat-threads/thread')
  expect(api.mock.calls[2][1]?.method).toBeUndefined()
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeEnabled()
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  await screen.findByRole('heading', { name: '범위를 좁힌 질문' })
  expect(api).toHaveBeenCalledTimes(4)
  expect(api.mock.calls[3][0]).toBe('/workspaces/one/chat-threads')
  expect(JSON.parse(api.mock.calls[3][1]!.body as string)).toEqual({ requestId: expect.any(String), threadId: null, parentBundleId: null, question: '범위를 좁힌 질문' })
  expect(api.mock.calls.some(([path]) => path.endsWith('/answer-jobs'))).toBe(false)
  expect(api.mock.calls.some(([, options]) => options?.method === 'DELETE')).toBe(false)
  expect(props.onCreated.mock.calls).toEqual([['thread'], ['new-thread']])
})

it('이력의 미리보기 실패 후 새 대화 시작은 원래 질문을 복원하고 재전송하지 않는다', async () => {
  api.mockResolvedValueOnce({ ...thread, jobs: [] }).mockRejectedValueOnce(new Error('근거가 변경되었습니다.'))
    .mockResolvedValueOnce({ ...thread, jobs: [] })
  render(<ChatPanel {...props} selectedId="thread" />)
  expect(await screen.findByRole('button', { name: '답변 받기' })).toBeEnabled()
  expect(screen.getByRole('button', { name: '새 대화로 다시 질문' })).toBeEnabled()
  await userEvent.click(screen.getByRole('button', { name: '답변 받기' }))
  await screen.findByRole('alert')
  await userEvent.click(screen.getByRole('button', { name: '새 대화로 다시 질문' }))
  await waitFor(() => expect(screen.getByRole('textbox')).toHaveValue('가상 질문'))
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeEnabled()
  expect(api).toHaveBeenCalledTimes(3)
  expect(props.onCreated).not.toHaveBeenCalled()
})

it('접수 정보 없이 이력을 다시 열어도 새 대화는 서버 작업 확인만 하고 AI를 재전송하지 않는다', async () => {
  api.mockResolvedValue({ ...thread, jobs: [] })
  render(<ChatPanel {...props} selectedId="thread" />)
  await userEvent.click(await screen.findByRole('button', { name: '새 대화로 다시 질문' }))
  await waitFor(() => expect(screen.getByRole('textbox')).toHaveValue('가상 질문'))
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeEnabled()
  expect(api.mock.calls.map(([path]) => path)).toEqual(['/workspaces/one/chat-threads/thread', '/workspaces/one/chat-threads/thread'])
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
  expect(props.onCreated).not.toHaveBeenCalled()
})

it.each(['QUEUED', 'RUNNING', 'CANCELLING'])('다시 연 이력에서 %s 작업이 뒤늦게 발견되면 새 대화를 시작하지 않는다', async status => {
  api.mockResolvedValueOnce({ ...thread, jobs: [] })
    .mockResolvedValueOnce({ ...thread, jobs: [{ ...thread.jobs[0], status, answer: null }] })
  render(<ChatPanel {...props} selectedId="thread" />)
  await screen.findByRole('button', { name: '새 대화로 다시 질문' })
  await userEvent.type(screen.getByRole('textbox'), '보존할 질문')
  await userEvent.click(screen.getByRole('button', { name: '새 대화로 다시 질문' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('현재 답변이 끝난 뒤')
  expect(screen.getByRole('heading', { name: '가상 질문' })).toBeInTheDocument()
  expect(screen.getByRole('textbox')).toHaveValue('보존할 질문')
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeDisabled()
  expect(screen.queryByRole('button', { name: '새 대화로 다시 질문' })).not.toBeInTheDocument()
  expect(vi.mocked(AnswerJobsPanel).mock.calls.at(-1)![0].initialJob?.status).toBe(status)
  expect(api).toHaveBeenCalledTimes(2)
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
  expect(props.onCreated).not.toHaveBeenCalled()
})

it('새 대화 전 작업 확인 실패는 원래 질문과 입력을 보존하고 다시 확인할 수 있다', async () => {
  api.mockResolvedValueOnce({ ...thread, jobs: [] }).mockRejectedValueOnce(new Error('작업 확인 실패'))
    .mockResolvedValueOnce({ ...thread, jobs: [] })
  render(<ChatPanel {...props} selectedId="thread" />)
  await screen.findByRole('button', { name: '새 대화로 다시 질문' })
  await userEvent.type(screen.getByRole('textbox'), '보존할 질문')
  await userEvent.click(screen.getByRole('button', { name: '새 대화로 다시 질문' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('작업 확인 실패')
  expect(screen.getByRole('heading', { name: '가상 질문' })).toBeInTheDocument()
  expect(screen.getByRole('textbox')).toHaveValue('보존할 질문')
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeDisabled()
  await userEvent.click(screen.getByRole('button', { name: '새 대화로 다시 질문' }))
  await waitFor(() => expect(screen.queryByRole('heading', { name: '가상 질문' })).not.toBeInTheDocument())
  expect(screen.getByRole('textbox')).toHaveValue('보존할 질문')
  expect(api).toHaveBeenCalledTimes(3)
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
})

it('준비 실패를 재시도하다 답변 접수 응답이 유실되면 새 대화 경로를 닫고 같은 ID로 확인한다', async () => {
  api.mockResolvedValueOnce(bundle).mockRejectedValueOnce(new Error('미리보기 실패'))
    .mockResolvedValueOnce(prepared).mockResolvedValueOnce({ status: 'NONE', approval: null })
    .mockResolvedValueOnce({ status: 'APPROVED', approval: approved })
    .mockRejectedValueOnce(new Error('접수 응답 유실')).mockResolvedValueOnce(completed)
  render(<ChatPanel {...props} />)
  await userEvent.type(screen.getByRole('textbox'), '가상 질문{Enter}')
  expect(await screen.findByRole('button', { name: '새 대화로 다시 질문' })).toBeEnabled()
  await userEvent.click(screen.getByRole('button', { name: '답변 받기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('접수 응답 유실')
  expect(screen.queryByRole('button', { name: '새 대화로 다시 질문' })).not.toBeInTheDocument()
  await userEvent.type(screen.getByRole('textbox'), '다음 질문')
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeDisabled()
  await userEvent.click(screen.getByRole('button', { name: '접수 상태 다시 확인' }))
  await screen.findByText('가상 답변 [R1]')
  expect(api).toHaveBeenCalledTimes(7)
  expect(api.mock.calls[5][0]).toBe('/workspaces/one/evidence-bundles/bundle/answer-jobs')
  expect(api.mock.calls[6][0]).toBe(api.mock.calls[5][0])
  expect(api.mock.calls[6][1]?.body).toBe(api.mock.calls[5][1]?.body)
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeEnabled()
})

it('접수 응답 유실 뒤 다시 열면 저장한 ID를 복원하고 명시적 확인만 같은 요청으로 보낸다', async () => {
  const scope = { workspaceId: 'one', bundleId: 'bundle', bundleDigest: 'digest' }
  api.mockResolvedValueOnce(bundle).mockResolvedValueOnce(prepared)
    .mockResolvedValueOnce({ status: 'NONE', approval: null }).mockResolvedValueOnce({ status: 'APPROVED', approval: approved })
    .mockImplementationOnce(async (_path, options) => {
      expect(readChatSubmission(scope)?.submission).toEqual(JSON.parse(options!.body as string))
      throw new Error('접수 응답 유실')
    })
  const first = render(<ChatPanel {...props} />)
  await userEvent.type(screen.getByRole('textbox'), '가상 질문{Enter}')
  await screen.findByRole('alert')
  const saved = readChatSubmission(scope)!
  expect(saved).toEqual({ ...scope, provider: 'CODEX', requestedModel: 'fixture-model', submission: {
    requestId: expect.any(String), approvalId: 'approval', digest: 'preview', approved: true,
  } })
  first.unmount()
  expect(readChatSubmission(scope)).toEqual(saved)
  api.mockReset()
  api.mockResolvedValueOnce({ ...thread, jobs: [] }).mockResolvedValueOnce({ ...completed, id: saved.submission.requestId })
  render(<ChatPanel {...props} selectedId="thread" />)
  await screen.findByRole('button', { name: '접수 상태 다시 확인' })
  expect(api).toHaveBeenCalledTimes(1)
  expect(api.mock.calls[0][1]?.method).toBeUndefined()
  expect(screen.queryByRole('button', { name: '새 대화로 다시 질문' })).not.toBeInTheDocument()
  await userEvent.type(screen.getByRole('textbox'), '다음 질문')
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeDisabled()
  await userEvent.click(screen.getByRole('button', { name: '접수 상태 다시 확인' }))
  await screen.findByText('가상 답변 [R1]')
  expect(api).toHaveBeenCalledTimes(2)
  expect(api.mock.calls[1][0]).toBe('/workspaces/one/evidence-bundles/bundle/answer-jobs')
  expect(JSON.parse(api.mock.calls[1][1]!.body as string)).toEqual(saved.submission)
  expect(readChatSubmission(scope)).toBeNull()
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeEnabled()
})

it('접수 POST 대기 중 화면을 떠나도 저장한 ID를 보존하고 늦은 이전 응답으로 지우지 않는다', async () => {
  const scope = { workspaceId: 'one', bundleId: 'bundle', bundleDigest: 'digest' }
  let finish!: (value: unknown) => void
  api.mockResolvedValueOnce(bundle).mockResolvedValueOnce(prepared)
    .mockResolvedValueOnce({ status: 'NONE', approval: null }).mockResolvedValueOnce({ status: 'APPROVED', approval: approved })
    .mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const first = render(<ChatPanel {...props} />)
  await userEvent.type(screen.getByRole('textbox'), '가상 질문{Enter}')
  await waitFor(() => expect(api).toHaveBeenCalledTimes(5))
  const saved = readChatSubmission(scope)!
  expect(saved.submission.requestId).toEqual(expect.any(String))
  expect(screen.queryByRole('button', { name: '새 대화로 다시 질문' })).not.toBeInTheDocument()
  const signal = api.mock.calls[4][1]!.signal!
  first.unmount()
  expect(signal.aborted).toBe(true)
  api.mockReset()
  api.mockResolvedValueOnce({ ...thread, jobs: [] })
  render(<ChatPanel {...props} selectedId="thread" />)
  await screen.findByRole('button', { name: '접수 상태 다시 확인' })
  await act(async () => finish({ ...completed, id: saved.submission.requestId }))
  expect(readChatSubmission(scope)).toEqual(saved)
  expect(screen.getByRole('button', { name: '접수 상태 다시 확인' })).toBeEnabled()
  expect(api).toHaveBeenCalledTimes(1)
})

it.each(['RUNNING', 'SUCCEEDED'])('다시 열 때 늦게 확인된 %s 작업은 저장한 접수와 일치할 때만 정리한다', async status => {
  const saved = { workspaceId: 'one', bundleId: 'bundle', bundleDigest: 'digest', provider: 'CODEX', requestedModel: 'fixture-model',
    submission: { requestId: 'saved-request', approvalId: 'approval', digest: 'preview', approved: true as const } }
  saveChatSubmission(saved)
  api.mockResolvedValueOnce({ ...thread, jobs: [{ ...thread.jobs[0], id: 'saved-request', status, answer: status === 'SUCCEEDED' ? completed.answer : null }] })
  render(<ChatPanel {...props} selectedId="thread" />)
  await screen.findByRole('heading', { name: '가상 질문' })
  expect(readChatSubmission(saved)).toBeNull()
  expect(vi.mocked(AnswerJobsPanel).mock.calls.at(-1)![0].initialJob?.id).toBe('saved-request')
  expect(screen.queryByRole('button', { name: '접수 상태 다시 확인' })).not.toBeInTheDocument()
  await userEvent.type(screen.getByRole('textbox'), '다음 질문')
  if (status === 'RUNNING') expect(screen.getByRole('button', { name: '질문 보내기' })).toBeDisabled()
  else expect(screen.getByRole('button', { name: '질문 보내기' })).toBeEnabled()
  expect(api).toHaveBeenCalledTimes(1)
  expect(api.mock.calls[0][1]?.method).toBeUndefined()
})

it.each([{ id: 'other-request' }, { approvalId: 'other-approval' }, { provider: 'CLAUDE' }, { requestedModel: 'other-model' }])('다른 접수 작업은 저장한 미확인 요청을 정리하지 않는다: %s', async mismatch => {
  const saved = { workspaceId: 'one', bundleId: 'bundle', bundleDigest: 'digest', provider: 'CODEX', requestedModel: 'fixture-model',
    submission: { requestId: 'saved-request', approvalId: 'approval', digest: 'preview', approved: true as const } }
  saveChatSubmission(saved)
  api.mockResolvedValueOnce({ ...thread, jobs: [{ ...thread.jobs[0], id: 'saved-request', ...mismatch }] })
  render(<ChatPanel {...props} selectedId="thread" />)
  await screen.findByRole('button', { name: '접수 상태 다시 확인' })
  expect(readChatSubmission(saved)).toEqual(saved)
  expect(screen.queryByText('가상 답변 [R1]')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '새 대화로 다시 질문' })).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(1)
})

it('다른 서재의 이력 응답에서는 저장한 접수를 복원하거나 정리하지 않는다', async () => {
  const saved = { workspaceId: 'one', bundleId: 'bundle', bundleDigest: 'digest', provider: 'CODEX', requestedModel: 'fixture-model',
    submission: { requestId: 'saved-request', approvalId: 'approval', digest: 'preview', approved: true as const } }
  saveChatSubmission(saved)
  api.mockResolvedValueOnce({ ...thread, bundles: [{ ...bundle, workspaceId: 'other' }], jobs: [{ ...thread.jobs[0], id: 'saved-request' }] })
  render(<ChatPanel {...props} selectedId="thread" />)
  expect(await screen.findByRole('alert')).toHaveTextContent('서재가 다릅니다')
  expect(readChatSubmission(saved)).toEqual(saved)
  expect(screen.queryByRole('button', { name: '접수 상태 다시 확인' })).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(1)
})

it.each([{ provider: 'CLAUDE' as const, model: 'fixture-model' }, { provider: 'CODEX' as const, model: 'other-model' }])('복원한 접수와 제공자·모델이 다르면 재사용하지 않는다: %s', async settings => {
  const saved = { workspaceId: 'one', bundleId: 'bundle', bundleDigest: 'digest', provider: 'CODEX', requestedModel: 'fixture-model',
    submission: { requestId: 'saved-request', approvalId: 'approval', digest: 'preview', approved: true as const } }
  saveChatSubmission(saved)
  api.mockResolvedValueOnce({ ...thread, jobs: [] }).mockResolvedValueOnce({ ...completed, id: 'saved-request' })
  const view = render(<ChatPanel {...props} selectedId="thread" connection={{ ...props.connection, ...settings }} />)
  await userEvent.click(await screen.findByRole('button', { name: '접수 상태 다시 확인' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('이전 제공자와 모델')
  expect(api).toHaveBeenCalledTimes(1)
  expect(readChatSubmission(saved)).toEqual(saved)
  expect(screen.queryByRole('button', { name: '새 대화로 다시 질문' })).not.toBeInTheDocument()
  view.rerender(<ChatPanel {...props} selectedId="thread" />)
  await userEvent.click(screen.getByRole('button', { name: '접수 상태 다시 확인' }))
  await screen.findByText('가상 답변 [R1]')
  expect(JSON.parse(api.mock.calls[1][1]!.body as string)).toEqual(saved.submission)
  expect(readChatSubmission(saved)).toBeNull()
})

it('세션 저장 실패는 답변 작업 POST 전에 전송을 중단한다', async () => {
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
  api.mockResolvedValueOnce(bundle).mockResolvedValueOnce(prepared)
    .mockResolvedValueOnce({ status: 'NONE', approval: null }).mockResolvedValueOnce({ status: 'APPROVED', approval: approved })
  render(<ChatPanel {...props} />)
  await userEvent.type(screen.getByRole('textbox'), '가상 질문{Enter}')
  expect(await screen.findByRole('alert')).toHaveTextContent('보관하지 못해 전송하지 않았습니다')
  expect(api).toHaveBeenCalledTimes(4)
  expect(api.mock.calls.some(([path]) => path.endsWith('/answer-jobs'))).toBe(false)
})

it('답변 접수 실패 후 새 질문을 막고 같은 요청 ID로 접수를 확인한 답변이 끝나면 이어 보낸다', async () => {
  api.mockResolvedValueOnce(bundle).mockResolvedValueOnce(prepared)
    .mockResolvedValueOnce({ status: 'NONE', approval: null })
    .mockResolvedValueOnce({ status: 'APPROVED', approval: approved })
    .mockRejectedValueOnce(new Error('답변 접수 응답 유실'))
    .mockResolvedValueOnce({ ...completed, status: 'RUNNING', answer: null })
    .mockResolvedValueOnce({ ...bundle, id: 'next-bundle', payload: { ...bundle.payload, question: '다음 질문', status: 'NO_EVIDENCE', items: [] } })
  render(<ChatPanel {...props} />)
  await userEvent.type(screen.getByRole('textbox'), '가상 질문{Enter}')
  expect(await screen.findByRole('alert')).toHaveTextContent('답변 접수 응답 유실')
  expect(screen.queryByRole('button', { name: '새 대화로 다시 질문' })).not.toBeInTheDocument()
  expect(screen.getByRole('heading', { name: '가상 질문' })).toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(5)
  const originalSubmission = JSON.parse(api.mock.calls[4][1]!.body as string)
  expect(originalSubmission).toEqual({ requestId: expect.any(String), approvalId: 'approval', digest: 'preview', approved: true })
  expect(originalSubmission.requestId).toMatch(/^[0-9a-f-]{36}$/)
  const input = screen.getByRole('textbox')
  await userEvent.type(input, '다음 질문')
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeDisabled()
  fireEvent.submit(input.closest('form')!)
  expect(api).toHaveBeenCalledTimes(5)
  expect(screen.queryByRole('heading', { name: '다음 질문' })).not.toBeInTheDocument()
  const retry = screen.getByRole('button', { name: '접수 상태 다시 확인' })
  expect(retry).toBeEnabled()
  await userEvent.click(retry)
  await waitFor(() => expect(screen.queryByRole('button', { name: '접수 상태 다시 확인' })).not.toBeInTheDocument())
  expect(api).toHaveBeenCalledTimes(6)
  expect(api.mock.calls[4][0]).toBe('/workspaces/one/evidence-bundles/bundle/answer-jobs')
  expect(api.mock.calls[5][0]).toBe(api.mock.calls[4][0])
  expect(api.mock.calls[5][1]?.method).toBe('POST')
  expect(JSON.parse(api.mock.calls[5][1]!.body as string)).toEqual(originalSubmission)
  expect(input).toHaveValue('다음 질문')
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeDisabled()
  fireEvent.submit(input.closest('form')!)
  expect(api).toHaveBeenCalledTimes(6)
  const panel = vi.mocked(AnswerJobsPanel).mock.calls.at(-1)![0]
  act(() => panel.onJobChange!(completed))
  const answer = screen.getByText('가상 답변 [R1]')
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeEnabled()
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  await screen.findByRole('heading', { name: '다음 질문' })
  expect(api).toHaveBeenCalledTimes(7)
  expect(api.mock.calls[6][0]).toBe('/workspaces/one/chat-threads')
  expect(JSON.parse(api.mock.calls[6][1]!.body as string)).toEqual({ requestId: expect.any(String), threadId: 'thread', parentBundleId: 'bundle', question: '다음 질문' })
  expect(screen.getByText('가상 답변 [R1]')).toBe(answer)
  expect(screen.getAllByRole('heading', { name: '가상 질문' })).toHaveLength(1)
})

it('선택한 대화에 질문을 추가한 뒤 다른 대화를 거쳐 돌아오면 전체 이력을 다시 조회한다', async () => {
  const next = { ...bundle, id: 'next-bundle', payload: { ...bundle.payload, question: '추가 질문', status: 'NO_EVIDENCE', items: [] } }
  const other = { ...bundle, id: 'other-bundle', payload: { ...bundle.payload, question: '다른 대화', status: 'NO_EVIDENCE', items: [], chatContext: { threadId: 'other' } } }
  api.mockResolvedValueOnce(thread).mockResolvedValueOnce(next)
    .mockResolvedValueOnce({ ...thread, id: 'other', bundles: [other], jobs: [] })
    .mockResolvedValueOnce({ ...thread, bundles: [bundle, next] })
  const view = render(<ChatPanel {...props} selectedId="thread" />)
  await screen.findByText('가상 답변 [R1]')
  await userEvent.type(screen.getByRole('textbox'), '추가 질문{Enter}')
  await screen.findByRole('heading', { name: '추가 질문' })
  expect(props.onCreated).toHaveBeenCalledExactlyOnceWith('thread')
  view.rerender(<ChatPanel {...props} selectedId="other" />)
  await screen.findByRole('heading', { name: '다른 대화' })
  view.rerender(<ChatPanel {...props} selectedId="thread" />)
  await screen.findByRole('heading', { name: '추가 질문' })
  expect(screen.getByText('가상 답변 [R1]')).toBeInTheDocument()
  expect(screen.queryByRole('heading', { name: '다른 대화' })).not.toBeInTheDocument()
  expect(api.mock.calls.map(([path]) => path)).toEqual([
    '/workspaces/one/chat-threads/thread', '/workspaces/one/chat-threads',
    '/workspaces/one/chat-threads/other', '/workspaces/one/chat-threads/thread',
  ])
})

it('자료를 찾는 동안 즉시 진행을 표시하고 중복 전송을 막는다', async () => {
  api.mockImplementation(() => new Promise(() => {}))
  const view = render(<ChatPanel {...props} />)
  await userEvent.type(screen.getByRole('textbox'), '가상 질문')
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  expect(screen.getByRole('status')).toHaveTextContent('확인해보겠습니다')
  expect(screen.getByRole('heading', { name: '가상 질문' })).toBeInTheDocument()
  fireEvent.submit(document.querySelector('form')!)
  expect(api).toHaveBeenCalledOnce()
  const signal = api.mock.calls[0][1]!.signal!
  view.unmount()
  expect(signal.aborted).toBe(true)
})

it('환영 로고의 o에만 캐릭터를 표시하고 오른쪽에는 중복 배치하지 않는다', () => {
  render(<ChatPanel {...props} />)
  const heading = screen.getByRole('heading', { name: 'Dougle' })
  const mascot = heading.querySelector('.wordmark-face img')
  expect(mascot).toHaveAttribute('alt', '')
  expect(mascot?.parentElement).toHaveAttribute('aria-hidden', 'true')
  expect(heading.querySelectorAll('img')).toHaveLength(1)
  expect(mascot).toHaveAttribute('src', expect.stringContaining('dougle-mascot-transparent.png'))
})

it('검색 결과가 없으면 짧은 안내만 표시한다', async () => {
  api.mockResolvedValue({ ...bundle, payload: { ...bundle.payload, status: 'NO_EVIDENCE', items: [] } })
  render(<ChatPanel {...props} />)
  await userEvent.type(screen.getByRole('textbox', { name: '질문' }), '가상 질문')
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  expect(await screen.findByText('질문과 관련된 내용을 찾지 못했어요.')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '자료 확인' })).not.toBeInTheDocument()
  expect(screen.queryByText(/자료를 확인하거나 질문을 바꿔/)).not.toBeInTheDocument()
  expect(screen.queryByText(/답변의 근거가 될 자료가 없어요/)).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '자료 더 찾아보기' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '브라우저 화면 조사' })).not.toBeInTheDocument()
  expect(api.mock.calls.some(([path]) => path.includes('investigation-plans'))).toBe(false)
})

it.each(['UNCHECKED', 'CHECKING', 'LOGIN_REQUIRED', 'TOOL_UNAVAILABLE', 'CHECK_FAILED'])('%s 상태는 입력과 전송을 잠그고 강제 submit도 보내지 않는다', state => {
  render(<ChatPanel {...props} connection={{ ...props.connection, state }} />)
  const input = screen.getByRole('textbox', { name: '질문' })
  expect(input).toBeDisabled()
  expect(input).toHaveAttribute('placeholder', 'AI를 연결해 주세요')
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeDisabled()
  fireEvent.change(input, { target: { value: '보내면 안 되는 가상 질문' } })
  fireEvent.submit(input.closest('form')!)
  expect(api).not.toHaveBeenCalled()
})

it('연결 확인 이후에만 입력을 풀고 연결이 사라지면 다시 잠근다', () => {
  const view = render(<ChatPanel {...props} connection={null} />)
  const input = screen.getByRole('textbox', { name: '질문' })
  expect(input).toBeDisabled()
  view.rerender(<ChatPanel {...props} />)
  expect(input).toBeEnabled()
  expect(input).toHaveAttribute('placeholder', '더글한테 물어보기')
  view.rerender(<ChatPanel {...props} connection={null} />)
  expect(input).toBeDisabled()
  expect(api).not.toHaveBeenCalled()
})

it('미연결 상태에서는 질문 전송만 잠그고 자료와 연결을 열 수 있다', async () => {
  render(<ChatPanel {...props} connection={null} />)
  expect(screen.getByRole('textbox', { name: '질문' })).toBeDisabled()
  expect(screen.getByPlaceholderText('AI를 연결해 주세요')).toBeDisabled()
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '파일 첨부' })).toBeEnabled()
  expect(screen.queryByRole('button', { name: 'AI 연결하기' })).not.toBeInTheDocument()
  expect(props.onSources).not.toHaveBeenCalled()
  expect(props.onConnect).not.toHaveBeenCalled()
  expect(api).not.toHaveBeenCalled()
})

it('시작 화면은 짧은 제목과 입력 안내만 표시한다', () => {
  render(<ChatPanel {...props} />)
  expect(screen.getByRole('heading', { name: 'Dougle' })).toBeInTheDocument()
  expect(screen.getByPlaceholderText('더글한테 물어보기')).toBeEnabled()
  expect(screen.queryByText('내 자료 기반 · 새 질문')).not.toBeInTheDocument()
  expect(screen.queryByText('자료 전송은 동의 후에만 진행됩니다.')).not.toBeInTheDocument()
  expect(screen.queryByText('흩어진 자료에서, 필요한 답을.')).not.toBeInTheDocument()
})

it('질문 전송으로 자료 확인과 답변 접수를 한 번만 하고 출처는 답변 아래에 표시한다', async () => {
  api.mockResolvedValueOnce(bundle).mockResolvedValueOnce(prepared).mockResolvedValueOnce({ status: 'NONE', approval: null })
    .mockResolvedValueOnce({ status: 'APPROVED', approval: approved }).mockResolvedValueOnce(completed)
  render(<ChatPanel {...props} />)
  await userEvent.type(screen.getByRole('textbox'), '가상 질문')
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  const answer = await screen.findByText('가상 답변 [R1]')
  expect(props.onCreated).toHaveBeenCalledWith('thread')
  expect(api).toHaveBeenCalledTimes(5)
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  expect(screen.queryByText('질문 관리')).not.toBeInTheDocument()
  expect(answer.compareDocumentPosition(screen.getByText('[R1] 가상 자료')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(api).toHaveBeenCalledWith('/workspaces/one/evidence-bundles/bundle/answer-preview', expect.objectContaining({ body: JSON.stringify({ provider: 'CODEX', requestedModel: 'fixture-model' }) }))
  expect(api.mock.calls.filter(([path]) => path.endsWith('answer-jobs'))).toHaveLength(1)
})

it('한글 조합 중 Enter는 전송하지 않는다', async () => {
  render(<ChatPanel {...props} />)
  const input = screen.getByRole('textbox')
  await userEvent.type(input, '가상')
  fireEvent.keyDown(input, { key: 'Enter', isComposing: true })
  expect(api).not.toHaveBeenCalled()
})

it('첨부 자체는 전송하지 않고 질문을 보낼 때 첨부와 함께 답변을 접수한다', async () => {
  const attached = { id: 'image', name: 'synthetic.png', mediaType: 'image/png', size: 8, characters: 8, truncated: false }
  api.mockResolvedValueOnce(attached).mockResolvedValueOnce({ ...bundle, payload: { ...bundle.payload, items: [{ ...bundle.payload.items[0], kind: 'ATTACHMENT_IMAGE', title: 'synthetic.png' }] } }).mockResolvedValueOnce(prepared).mockResolvedValueOnce({ status: 'NONE', approval: null }).mockResolvedValueOnce({ status: 'APPROVED', approval: approved }).mockResolvedValueOnce(completed)
  render(<ChatPanel {...props} />)
  await userEvent.upload(screen.getByLabelText('질문에 첨부할 파일'), new File(['synthetic'], 'synthetic.png', { type: 'image/png' }))
  expect(await screen.findByText('synthetic.png')).toBeInTheDocument()
  await userEvent.type(screen.getByRole('textbox'), '이미지를 확인해 주세요')
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  expect(await screen.findByText('[R1] synthetic.png · 첨부 이미지')).toBeInTheDocument()
  await screen.findByText('가상 답변 [R1]')
  expect(api).toHaveBeenCalledTimes(6)
  const submission = api.mock.calls.find(([path]) => path === '/workspaces/one/chat-threads')!
  expect(submission[1]?.method).toBe('POST')
  expect(JSON.parse(submission[1]!.body as string)).toEqual({ requestId: expect.any(String), threadId: null, parentBundleId: null, question: '이미지를 확인해 주세요', attachmentIds: ['image'] })
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  expect(props.onSources).not.toHaveBeenCalled()
})

it('Claude 이미지 질문은 전송 전에 안내하며 첨부와 질문을 보존한다', async () => {
  api.mockResolvedValue({ id: 'image', name: 'synthetic.png', mediaType: 'image/png', size: 8, characters: 8, truncated: false })
  render(<ChatPanel {...props} connection={{ ...props.connection, provider: 'CLAUDE' }} />)
  await userEvent.upload(screen.getByLabelText('질문에 첨부할 파일'), new File(['synthetic'], 'synthetic.png', { type: 'image/png' }))
  await screen.findByText('synthetic.png')
  await userEvent.type(screen.getByRole('textbox'), '이미지 질문')
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Codex')
  expect(screen.getByRole('textbox')).toHaveValue('이미지 질문')
  expect(api).toHaveBeenCalledTimes(1)
})

it('다른 질문 선택 후 늦은 이전 응답을 무시한다', async () => {
  let finish!: (value: unknown) => void
  api.mockImplementation(path => path.endsWith('/old') ? new Promise(resolve => { finish = resolve }) : Promise.resolve([]))
  const view = render(<ChatPanel {...props} selectedId="old" />)
  view.rerender(<ChatPanel {...props} selectedId={null} />)
  await act(async () => finish({ ...thread, id: 'old' }))
  await waitFor(() => expect(screen.getByRole('textbox')).toBeEnabled())
  expect(screen.queryByText('[R1] 가상 자료')).not.toBeInTheDocument()
})

it('연결 없이도 이전 답변을 읽으며 새 AI 호출을 하지 않는다', async () => {
  api.mockResolvedValue({ ...thread, jobs: [{ ...thread.jobs[0], answer: '보존한 답변' }] })
  render(<ChatPanel {...props} selectedId="thread" connection={null} />)
  expect(await screen.findByText('보존한 답변')).toBeInTheDocument()
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
})

it('이전 편집형 지식이 포함된 미완료 질문은 그대로 전송하지 않고 원본으로 다시 질문한다', async () => {
  const legacy = { ...bundle, payload: { ...bundle.payload, items: [{ ...bundle.payload.items[0], kind: 'KNOWLEDGE' }] } }
  api.mockResolvedValueOnce({ ...thread, bundles: [legacy], jobs: [] }).mockResolvedValueOnce({ ...bundle, payload: { ...bundle.payload, status: 'NO_EVIDENCE', items: [] } })
  render(<ChatPanel {...props} selectedId="thread" />)
  await userEvent.click(await screen.findByRole('button', { name: '원본으로 다시 질문' }))
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
  expect(screen.queryByRole('button', { name: '이 자료로 답변 준비' })).not.toBeInTheDocument()
  expect(screen.getByRole('textbox', { name: '질문' })).toHaveValue('가상 질문')
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  const submission = api.mock.calls.find(([path]) => path === '/workspaces/one/chat-threads')!
  expect(submission[1]?.method).toBe('POST')
  expect(JSON.parse(submission[1]!.body as string)).toEqual({ requestId: expect.any(String), threadId: null, parentBundleId: null, question: '가상 질문' })
})

it('최근 질문 클릭과 새 질문은 목록 조회 이외의 API를 부르지 않는다', async () => {
  api.mockResolvedValue([{ id: 'thread', question: '가상 질문' }])
  const select = vi.fn()
  render(<QuestionHistory workspaceId="one" revision={0} selectedId="thread" onSelect={select} />)
  await userEvent.click(await screen.findByRole('button', { name: '가상 질문' }))
  await userEvent.click(screen.getByRole('button', { name: '새 질문' }))
  expect(select.mock.calls).toEqual([['thread'], [null]])
  expect(api).toHaveBeenCalledTimes(1)
  expect(api.mock.calls[0][0]).toBe('/workspaces/one/chat-threads')
  expect(screen.queryByText('최근 질문')).not.toBeInTheDocument()
})

it('질문이 없으면 사이드바 제목과 시작 안내를 표시하지 않는다', async () => {
  api.mockResolvedValue([])
  render(<QuestionHistory workspaceId="one" revision={0} selectedId={null} onSelect={vi.fn()} />)
  await waitFor(() => expect(api).toHaveBeenCalledOnce())
  expect(screen.queryByText('최근 질문')).not.toBeInTheDocument()
  expect(screen.queryByText('첫 질문을 시작해 보세요.')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '새 질문' })).not.toBeInTheDocument()
})

it('이력 열기는 재전송하지 않고 대화에는 관리나 삭제를 표시하지 않는다', async () => {
  api.mockResolvedValue({ ...thread, jobs: [] })
  render(<ChatPanel {...props} selectedId="thread" />)
  await screen.findByRole('button', { name: '답변 받기' })
  expect(screen.queryByRole('region', { name: '참고 자료' })).not.toBeInTheDocument()
  expect(screen.queryByText('질문 관리')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '질문과 답변 삭제' })).not.toBeInTheDocument()
  expect(api.mock.calls.every(([, options]) => !options?.method || options.method === 'GET')).toBe(true)
})
