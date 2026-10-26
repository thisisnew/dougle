import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ChatPanel from './ChatPanel'
import { request } from './api'
import type { OperationalToolsPanelProps } from './OperationalToolsPanel'

const { attachment } = vi.hoisted(() => ({ attachment: {
  id: 'operational-attachment', name: 'synthetic-mysql-capture.txt', mediaType: 'text/plain', size: 120, characters: 120, truncated: false,
} }))
vi.mock('./api', async importOriginal => ({ ...await importOriginal<typeof import('./api')>(), request: vi.fn() }))
vi.mock('./OperationalToolsPanel', () => ({ default: ({ workspaceId, disabled, onCaptured, onBusyChange }: OperationalToolsPanelProps) => <section aria-label="Synthetic operational tool boundary">
  <button type="button" disabled={disabled} onClick={() => onCaptured(workspaceId!, attachment)}>Fixture capture</button>
  <button type="button" disabled={disabled} onClick={() => onCaptured('other-workspace', attachment)}>Fixture foreign capture</button>
  <button type="button" onClick={() => onBusyChange?.(true)}>Fixture start operation</button>
  <button type="button" onClick={() => onBusyChange?.(false)}>Fixture finish operation</button>
</section> }))

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
      const summary = file instanceof File && largeAttachments.find(item => item.name === file.name && item.size === file.size)
      if (summary) return summary
    }
    if (method === 'DELETE' && [attachment, ...largeAttachments].some(item => path === `${base}/attachments/${item.id}`)) return undefined
    unexpected.push(`${method} ${path}`)
    throw new Error(`Unexpected test API: ${method} ${path}`)
  })
})
afterEach(() => { expect(unexpected).toEqual([]) })

async function openThread(overrides: { onOperationalBusyChange?: (busy: boolean) => void } = {}) {
  const view = render(<ChatPanel {...props} {...overrides} />)
  await screen.findByRole('heading', { name: parent.payload.question })
  await waitFor(() => expect(screen.getByRole('textbox', { name: '질문' })).toBeEnabled())
  return view
}
function writes() { return api.mock.calls.filter(([, options]) => options?.method === 'POST') }
function deletes() { return api.mock.calls.filter(([, options]) => options?.method === 'DELETE').map(([path]) => path) }
async function fillAttachmentCapacity() {
  const files = largeAttachments.map(item => new File([new Uint8Array(item.size)], item.name, { type: item.mediaType }))
  await userEvent.upload(screen.getByLabelText('질문에 첨부할 파일'), files)
  await waitFor(() => expect(screen.getByRole('button', { name: 'Fixture capture' })).toBeEnabled())
  for (const item of largeAttachments) expect(screen.getByTitle(item.name)).toBeVisible()
  expect(writes().map(([path]) => path)).toEqual([`${base}/attachments`, `${base}/attachments`])
}

it('keeps the existing thread and latest parent; attachment handoff starts no AI until explicit submit', async () => {
  await openThread()
  await userEvent.click(screen.getByRole('button', { name: 'Fixture capture' }))
  expect(await screen.findByText(attachment.name)).toBeVisible()
  expect(screen.getByRole('heading', { name: root.payload.question })).toBeVisible()
  expect(screen.getByRole('heading', { name: parent.payload.question })).toBeVisible()
  expect(onCreated).not.toHaveBeenCalled()
  expect(api.mock.calls.map(([path]) => path)).toEqual([`${base}/chat-threads/${threadId}`])
  await userEvent.type(screen.getByRole('textbox', { name: '질문' }), next.payload.question)
  expect(writes()).toHaveLength(0)
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  await waitFor(() => expect(writes().some(([path]) => path === `${answerBase}/answer-jobs`)).toBe(true))
  const calls = writes()
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

it('ignores a cross-workspace capture rather than attaching it to the selected thread', async () => {
  await openThread()
  await userEvent.click(screen.getByRole('button', { name: 'Fixture foreign capture' }))
  expect(screen.queryByText(attachment.name)).not.toBeInTheDocument()
  expect(writes()).toHaveLength(0)
  await userEvent.type(screen.getByRole('textbox', { name: '질문' }), next.payload.question)
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  await waitFor(() => expect(onCreated).toHaveBeenCalledWith(threadId))
  expect(JSON.parse(writes()[0][1]!.body as string)).toEqual({ requestId: expect.any(String), threadId,
    parentBundleId: parent.id, question: next.payload.question })
})

it('operational busy state locks composer, uploads and submit handler without losing the draft', async () => {
  await openThread()
  const composer = screen.getByRole('textbox', { name: '질문' })
  await userEvent.type(composer, 'Synthetic draft retained')
  await userEvent.click(screen.getByRole('button', { name: 'Fixture start operation' }))
  expect(composer).toBeDisabled()
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '파일 첨부' })).toBeDisabled()
  expect(screen.getByLabelText('질문에 첨부할 파일')).toBeDisabled()
  fireEvent.submit(composer.closest('form')!)
  expect(writes()).toHaveLength(0)
  await userEvent.click(screen.getByRole('button', { name: 'Fixture finish operation' }))
  expect(composer).toBeEnabled()
  expect(composer).toHaveValue('Synthetic draft retained')
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeEnabled()
  expect(onCreated).not.toHaveBeenCalled()
})

it('propagates operational busy to the parent and always releases it on unmount', async () => {
  const busy = vi.fn()
  const view = await openThread({ onOperationalBusyChange: busy })
  expect(busy).toHaveBeenLastCalledWith(false)
  await userEvent.click(screen.getByRole('button', { name: 'Fixture start operation' }))
  expect(busy).toHaveBeenLastCalledWith(true)
  await userEvent.click(screen.getByRole('button', { name: 'Fixture finish operation' }))
  expect(busy).toHaveBeenLastCalledWith(false)
  await userEvent.click(screen.getByRole('button', { name: 'Fixture start operation' }))
  expect(busy).toHaveBeenLastCalledWith(true)
  view.unmount()
  expect(busy).toHaveBeenLastCalledWith(false)
  expect(writes()).toHaveLength(0)
})

it('retains a capacity-rejected receipt and retries adoption locally after removing one 5 MiB file', async () => {
  const view = await openThread()
  await fillAttachmentCapacity()
  const composer = screen.getByRole('textbox', { name: '질문' })
  await userEvent.type(composer, next.payload.question)
  await userEvent.click(screen.getByRole('button', { name: 'Fixture capture' }))
  expect(await screen.findByText('조회 결과는 저장되었습니다. 기존 첨부를 제거한 뒤 다시 첨부하세요.')).toBeVisible()
  expect(screen.queryByTitle(attachment.name)).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Fixture capture' })).toBeDisabled()
  fireEvent.submit(composer.closest('form')!)
  expect(writes()).toHaveLength(2)
  await userEvent.click(screen.getByRole('button', { name: `${largeAttachments[0].name} 첨부 제거` }))
  await waitFor(() => expect(screen.queryByTitle(largeAttachments[0].name)).not.toBeInTheDocument())
  await userEvent.click(screen.getByRole('button', { name: '조회 결과 다시 첨부' }))
  expect(await screen.findByTitle(attachment.name)).toBeVisible()
  expect(screen.queryByRole('button', { name: '조회 결과 다시 첨부' })).not.toBeInTheDocument()
  expect(writes()).toHaveLength(2)
  expect(deletes()).toEqual([`${base}/attachments/${largeAttachments[0].id}`])
  expect(screen.getByRole('heading', { name: parent.payload.question })).toBeVisible()
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  await waitFor(() => expect(writes().some(([path]) => path === `${answerBase}/answer-jobs`)).toBe(true))
  const request = writes().find(([path]) => path === `${base}/chat-threads`)!
  expect(JSON.parse(request[1]!.body as string)).toEqual({ requestId: expect.any(String), threadId,
    parentBundleId: parent.id, question: next.payload.question, attachmentIds: [largeAttachments[1].id, attachment.id] })
  expect(api.mock.calls.some(([path]) => path.includes('/operational-captures'))).toBe(false)
  expect(onCreated).toHaveBeenCalledExactlyOnceWith(threadId)
  view.unmount()
  expect(deletes()).toEqual([`${base}/attachments/${largeAttachments[0].id}`])
})

it('explicit exclusion deletes only the unbound captured attachment once and unblocks submission', async () => {
  const view = await openThread()
  await fillAttachmentCapacity()
  await userEvent.type(screen.getByRole('textbox', { name: '질문' }), next.payload.question)
  await userEvent.click(screen.getByRole('button', { name: 'Fixture capture' }))
  await userEvent.click(await screen.findByRole('button', { name: '조회 결과 제외' }))
  expect(deletes()).toEqual([`${base}/attachments/${attachment.id}`])
  expect(screen.queryByRole('button', { name: '조회 결과 다시 첨부' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeEnabled()
  for (const item of largeAttachments) expect(screen.getByTitle(item.name)).toBeVisible()
  expect(onCreated).not.toHaveBeenCalled()
  view.unmount()
  expect(deletes()).toHaveLength(3)
  expect(deletes()).toEqual(expect.arrayContaining([`${base}/attachments/${attachment.id}`,
    ...largeAttachments.map(item => `${base}/attachments/${item.id}`)]))
  expect(writes()).toHaveLength(2)
})

it('unmount cleans a retained unbound receipt and both unsent uploads without any recapture', async () => {
  const view = await openThread()
  await fillAttachmentCapacity()
  await userEvent.click(screen.getByRole('button', { name: 'Fixture capture' }))
  await screen.findByRole('button', { name: '조회 결과 다시 첨부' })
  expect(deletes()).toHaveLength(0)
  view.unmount()
  expect(deletes()).toHaveLength(3)
  expect(deletes()).toEqual(expect.arrayContaining([`${base}/attachments/${attachment.id}`,
    ...largeAttachments.map(item => `${base}/attachments/${item.id}`)]))
  expect(writes()).toHaveLength(2)
  expect(onCreated).not.toHaveBeenCalled()
})
