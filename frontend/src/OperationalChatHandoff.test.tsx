import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ChatPanel from './ChatPanel'
import { request } from './api'

const { attachment } = vi.hoisted(() => ({ attachment: {
  id: 'operational-attachment', name: 'synthetic-mysql-capture.txt', mediaType: 'text/plain', size: 120, characters: 120, truncated: false,
} }))
vi.mock('./api', async importOriginal => ({ ...await importOriginal<typeof import('./api')>(), request: vi.fn() }))
const api = vi.mocked(request)
const largeAttachments = ['one', 'two'].map(suffix => ({ id: `large-${suffix}`, name: `large-${suffix}.txt`,
  mediaType: 'text/plain', size: 5 * 1024 * 1024, characters: 3000, truncated: true }))
const base = '/workspaces/one'
const threadId = 'existing-thread'
const root = { id: 'root-bundle', workspaceId: 'one', digest: 'root-digest', payload: {
  question: 'Original synthetic question', status: 'READY', chatContext: { threadId, parentBundleId: null },
  items: [{ kind: 'ORIGINAL', label: 'R1', title: 'Synthetic source', quote: 'Synthetic evidence', startLine: 1, endLine: 1 }],
} }
const parent = { ...root, id: 'latest-bundle', digest: 'latest-digest', payload: {
  ...root.payload, question: 'Existing synthetic followup', chatContext: { threadId, parentBundleId: root.id },
} }
const next = { ...root, id: 'captured-followup', digest: 'followup-digest', payload: {
  ...root.payload, question: 'Explain the captured result', chatContext: { threadId, parentBundleId: parent.id },
  items: [{ ...root.payload.items[0], kind: 'ATTACHMENT_TEXT', title: attachment.name }],
} }
const job = { id: 'root-job', workspaceId: 'one', bundleId: root.id, approvalId: 'root-approval', status: 'SUCCEEDED',
  provider: 'CODEX', requestedModel: 'synthetic-model', answer: 'Original synthetic answer', error: null }
const answerBase = `${base}/evidence-bundles/${next.id}`
const approval = { id: 'followup-approval', bundleId: next.id, digest: 'followup-preview', provider: 'CODEX', requestedModel: 'synthetic-model' }
const onCreated = vi.fn()
const props = { workspaceId: 'one', selectedId: threadId,
  connection: { provider: 'CODEX' as const, state: 'AUTHENTICATED', model: 'synthetic-model' },
  onCreated, onConnect: vi.fn(), onSources: vi.fn() }
let unexpected: string[] = []

beforeEach(() => {
  vi.clearAllMocks(); api.mockReset(); unexpected = []
  for (const key of Object.keys(sessionStorage)) if (key.startsWith('dougle.chat-submission.')) sessionStorage.removeItem(key)
  api.mockImplementation(async (path, options) => {
    const method = options?.method ?? 'GET'
    if (method === 'GET' && path === `${base}/chat-threads/${threadId}`) return { id: threadId, bundles: [root, parent],
      jobs: [job, { ...job, id: 'parent-job', bundleId: parent.id, answer: 'Existing synthetic answer' }] }
    if (method === 'POST' && path === `${base}/chat-threads`) return next
    if (method === 'POST' && path === `${answerBase}/answer-preview`) return { bundleId: next.id, bundleDigest: next.digest,
      provider: 'CODEX', requestedModel: 'synthetic-model', digest: approval.digest, validation: { eligible: true } }
    if (method === 'GET' && path === `${answerBase}/answer-approval`) return { status: 'NONE', approval: null }
    if (method === 'POST' && path === `${answerBase}/answer-approval`) return { status: 'APPROVED', approval }
    if (method === 'POST' && path === `${answerBase}/answer-jobs`) return { ...job, id: 'followup-job', bundleId: next.id,
      approvalId: approval.id, answer: 'Captured synthetic answer' }
    if (method === 'POST' && path === `${base}/attachments` && options?.body instanceof FormData) {
      const file = options.body.get('file')
      const summary = file instanceof File && file.name === attachment.name ? attachment : undefined
      if (summary) return summary
    }
    if (method === 'DELETE' && [attachment, ...largeAttachments].some(item => path === `${base}/attachments/${item.id}`)) return undefined
    unexpected.push(`${method} ${path}`)
    throw new Error(`Unexpected test API: ${method} ${path}`)
  })
})
afterEach(() => { expect(unexpected).toEqual([]) })

async function openThread() {
  const view = render(<ChatPanel {...props} />)
  await screen.findByRole('heading', { name: parent.payload.question })
  await waitFor(() => expect(screen.getByRole('textbox', { name: '질문' })).toBeEnabled())
  return view
}
function writes() { return api.mock.calls.filter(([, options]) => options?.method === 'POST') }
it('keeps the existing thread and latest parent; normal file upload starts no AI until explicit submit', async () => {
  await openThread()
  await userEvent.upload(screen.getByLabelText('질문에 첨부할 파일'), new File(['synthetic'], attachment.name, { type: attachment.mediaType }))
  expect(await screen.findByText(attachment.name)).toBeVisible()
  expect(screen.getByRole('heading', { name: root.payload.question })).toBeVisible()
  expect(screen.getByRole('heading', { name: parent.payload.question })).toBeVisible()
  expect(onCreated).not.toHaveBeenCalled()
  expect(api.mock.calls.map(([path]) => path)).toEqual([base + '/chat-threads/' + threadId, base + '/attachments'])
  await userEvent.type(screen.getByRole('textbox', { name: '질문' }), next.payload.question)
  expect(writes()).toHaveLength(1)
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  await waitFor(() => expect(writes().some(([path]) => path === `${answerBase}/answer-jobs`)).toBe(true))
  const calls = writes().filter(([path]) => path !== base + '/attachments')
  expect(calls.map(([path]) => path)).toEqual([`${base}/chat-threads`, `${answerBase}/answer-preview`,
    `${answerBase}/answer-approval`, `${answerBase}/answer-jobs`])
  expect(JSON.parse(calls[0][1]!.body as string)).toEqual({ requestId: expect.any(String), threadId,
    parentBundleId: parent.id, question: next.payload.question, attachmentIds: [attachment.id] })
  expect(JSON.parse(calls[3][1]!.body as string)).toMatchObject({ approvalId: approval.id, digest: approval.digest, approved: true })
  expect(onCreated).toHaveBeenCalledExactlyOnceWith(threadId)
  expect(screen.getByRole('heading', { name: root.payload.question })).toBeVisible()
  expect(screen.getByRole('heading', { name: parent.payload.question })).toBeVisible()
  expect(screen.getByRole('heading', { name: next.payload.question })).toBeVisible()
  expect(api.mock.calls.some(([path, options]) => path.includes('/attachments/') && options?.method === 'DELETE')).toBe(false)
})


it('does not mount manual tools in a reopened conversation', async () => {
  await openThread()
  for (const name of ['운영 자료 조회', '조회 양식 열기', 'DB 데이터 조회', '조회 결과 다시 첨부']) expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '파일 첨부' })).toBeEnabled()
  expect(writes()).toHaveLength(0)
  expect(api.mock.calls.map(([path]) => path)).toEqual([base + '/chat-threads/' + threadId])
})
