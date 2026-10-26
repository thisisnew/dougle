import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ConversationReadPanel, { type ConversationScope } from './ConversationReadPanel'
import { request } from './api'

vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(), request: vi.fn() }))
const api = vi.mocked(request)
const scope: ConversationScope = { jobId: 'plan', scopeId: 'scope', revision: 1, workspaceId: 'workspace', threadId: 'thread', parentBundleId: 'bundle', tool: 'MYSQL_READ', expiresAt: '2099-01-01T00:00:00Z', sessionOnly: true, executionAllowed: false, internalResolutionRequired: false, questions: [] }
const preview = { id: 'read', workspaceId: 'workspace', threadId: 'thread', parentBundleId: 'bundle', tool: 'MYSQL_READ', sessionOnly: true, scopeId: 'scope', scopeRevision: 1, digest: 'digest', queryPreview: 'synthetic bounded scope', expiresAt: scope.expiresAt, maxRows: 10, maxBytes: 4096, timeoutSeconds: 10 }
const result = { ...preview, status: 'SUCCEEDED', output: 'synthetic result', rows: 1, truncated: false }
const capture = { id: 'read', threadId: 'thread', parentBundleId: 'bundle', digest: 'capture-digest', text: 'synthetic bounded evidence', bytes: 26, truncated: false, expiresAt: scope.expiresAt, sessionOnly: true }
const receipt = { id: 'read', threadId: 'thread', parentBundleId: 'bundle', status: 'CAPTURED', attachment: { id: 'attachment' }, contentHash: 'hash', externalCalls: 0 }
const base = '/workspaces/workspace/conversation-operational-reads'
beforeEach(() => {
  api.mockReset()
  api.mockImplementation(async path => path.endsWith('/capture-preview') ? capture : path.endsWith('/capture') ? receipt : path.endsWith('/preview') ? preview : result)
})
afterEach(() => vi.useRealTimers())
const props = () => ({ scope, disabled: false, onCaptured: vi.fn(async () => {}), onBusyChange: vi.fn() })
async function prepare() {
  fireEvent.click(screen.getByRole('button', { name: '조회 범위 확인' }))
  await screen.findByRole('button', { name: '이 범위만 읽기' })
}
async function execute() {
  await prepare()
  fireEvent.click(screen.getByRole('button', { name: '이 범위만 읽기' }))
  await screen.findByRole('button', { name: '이 자료로 답변 받기' })
}

it('requires separate scope and answer consent without any manual query fields', async () => {
  const callbacks = props()
  render(<ConversationReadPanel {...callbacks} />)
  expect(api).not.toHaveBeenCalled()
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  await prepare()
  expect(api).toHaveBeenCalledTimes(1)
  expect(JSON.parse(api.mock.calls[0][1]!.body as string)).toEqual({ scopeId: 'scope', scopeRevision: 1 })
  fireEvent.click(screen.getByRole('button', { name: '이 범위만 읽기' }))
  await screen.findByRole('button', { name: '이 자료로 답변 받기' })
  expect(callbacks.onCaptured).not.toHaveBeenCalled()
  expect(api.mock.calls.map(([path]) => path)).toEqual([`${base}/preview`, `${base}/read/execute`, `${base}/read/capture-preview`])
  fireEvent.click(screen.getByRole('button', { name: '이 자료로 답변 받기' }))
  await waitFor(() => expect(callbacks.onCaptured).toHaveBeenCalledWith(receipt, expect.any(AbortSignal)))
  expect(JSON.parse(api.mock.calls[3][1]!.body as string)).toEqual({ requestId: expect.any(String), digest: 'capture-digest', approved: true })
})

it.each(['workspaceId', 'threadId', 'parentBundleId', 'scopeId', 'scopeRevision', 'tool'])('rejects a mismatched preview %s before execution', async field => {
  api.mockResolvedValue({ ...preview, [field]: 'other' })
  render(<ConversationReadPanel {...props()} />)
  fireEvent.click(screen.getByRole('button', { name: '조회 범위 확인' }))
  await screen.findByRole('alert')
  expect(screen.queryByRole('button', { name: '이 범위만 읽기' })).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(1)
})

it('reconciles an uncertain read by GET without repeating its POST', async () => {
  api.mockImplementation(async path => {
    if (path.endsWith('/execute')) throw new Error('connection lost')
    return path.endsWith('/capture-preview') ? capture : path.endsWith('/preview') ? preview : result
  })
  render(<ConversationReadPanel {...props()} />)
  await prepare()
  fireEvent.click(screen.getByRole('button', { name: '이 범위만 읽기' }))
  await screen.findByRole('alert')
  expect(api).toHaveBeenCalledTimes(2)
  fireEvent.click(screen.getByRole('button', { name: '조회 상태 확인' }))
  await screen.findByRole('button', { name: '이 자료로 답변 받기' })
  expect(api.mock.calls.filter(([path]) => path.endsWith('/execute'))).toHaveLength(1)
  expect(api.mock.calls[2]).toEqual([`${base}/read`, expect.objectContaining({ signal: expect.any(AbortSignal) })])
})

it('recovers an uncertain capture by GET and requires fresh answer consent without repeating capture', async () => {
  const callbacks = props()
  api.mockImplementation(async path => {
    if (path.endsWith('/capture')) throw new Error('connection lost')
    if (path.endsWith('/capture-receipt')) return receipt
    return path.endsWith('/capture-preview') ? capture : path.endsWith('/preview') ? preview : result
  })
  render(<ConversationReadPanel {...callbacks} />)
  await execute()
  fireEvent.click(screen.getByRole('button', { name: '이 자료로 답변 받기' }))
  await screen.findByRole('alert')
  expect(callbacks.onCaptured).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '저장 상태 확인' }))
  await screen.findByRole('button', { name: '저장한 자료로 답변 받기' })
  expect(callbacks.onCaptured).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '저장한 자료로 답변 받기' }))
  fireEvent.click(screen.getByRole('button', { name: '저장한 자료로 답변 받기' }))
  await waitFor(() => expect(callbacks.onCaptured).toHaveBeenCalledTimes(1))
  const posts = api.mock.calls.filter(([path]) => path.endsWith('/capture'))
  expect(posts).toHaveLength(1)
  expect(api.mock.calls.filter(([path]) => path.endsWith('/capture-receipt'))).toEqual([[`${base}/read/capture-receipt`, expect.objectContaining({ signal: expect.any(AbortSignal) })]])
})

it('fails closed on unknown read status without exposing or capturing output', async () => {
  api.mockImplementation(async path => path.endsWith('/preview') ? preview : { ...result, status: 'UNKNOWN' })
  render(<ConversationReadPanel {...props()} />)
  await prepare()
  fireEvent.click(screen.getByRole('button', { name: '이 범위만 읽기' }))
  await screen.findByRole('alert')
  expect(screen.queryByText(result.output)).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '이 자료로 답변 받기' })).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(2)
})

it('releases busy on a hard deadline even when transport ignores abort and ignores late success', async () => {
  const callbacks = props()
  let resolve!: (value: unknown) => void
  api.mockImplementation(async path => path.endsWith('/preview') ? preview : new Promise(done => { resolve = done }))
  render(<ConversationReadPanel {...callbacks} />)
  await prepare()
  vi.useFakeTimers()
  fireEvent.click(screen.getByRole('button', { name: '이 범위만 읽기' }))
  await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })
  expect(screen.getByRole('alert')).toHaveTextContent('응답을 확인하지 못했습니다')
  expect(callbacks.onBusyChange).toHaveBeenLastCalledWith(false)
  expect(screen.getByRole('button', { name: '조회 상태 확인' })).toBeEnabled()
  await act(async () => resolve(result))
  expect(api).toHaveBeenCalledTimes(2)
  expect(screen.queryByRole('button', { name: '이 자료로 답변 받기' })).not.toBeInTheDocument()
})

it('releases the latest parent busy callback on unmount and never captures a late response', async () => {
  const callbacks = props()
  let resolve!: (value: unknown) => void
  api.mockImplementation(async path => path.endsWith('/preview') ? preview : new Promise(done => { resolve = done }))
  const view = render(<ConversationReadPanel {...callbacks} />)
  await prepare()
  fireEvent.click(screen.getByRole('button', { name: '이 범위만 읽기' }))
  const latest = vi.fn()
  view.rerender(<ConversationReadPanel {...callbacks} onBusyChange={latest} />)
  view.unmount()
  expect(latest).toHaveBeenLastCalledWith(false)
  await act(async () => resolve(result))
  expect(api).toHaveBeenCalledTimes(2)
  expect(callbacks.onCaptured).not.toHaveBeenCalled()
})
