import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import BrowserInvestigationPanel from './BrowserInvestigationPanel'
import { request } from './api'

vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(), request: vi.fn() }))
const api = vi.mocked(request)
const props = { workspaceId: 'workspace', bundleId: 'bundle' }
const preview = { bundleId: 'bundle', digest: 'digest', scope: { url: 'https://example.com/app', allowedGetPaths: ['/app', '/api/status'] },
  expiresAt: 'future', maxAiCalls: 0, limits: { maxSeconds: 30, maxRequests: 32, maxResponseBytes: 524288, maxTotalBytes: 4194304, maxTextCharacters: 6000 } }
const job = { id: 'job', workspaceId: 'workspace', bundleId: 'bundle', status: 'SUCCEEDED',
  result: { url: preview.scope.url, capturedAt: 'now', title: 'Fixture', text: '<script>untrusted</script>',
    status: 'CAPTURED', truncated: false, observations: [{ method: 'GET', path: '/api/status', status: 200, blocked: false }] } }

beforeEach(() => {
  api.mockReset()
  api.mockImplementation(async (path, options) => path.endsWith('/capability') ? { status: 'AVAILABLE', message: 'Isolated' }
    : path.endsWith('/preview') ? preview : options?.method === 'POST' ? job : [])
})
afterEach(() => { cleanup(); vi.useRealTimers() })

function deferredJob() {
  let resolve!: (value: typeof job) => void
  const promise = new Promise<typeof job>(done => { resolve = done })
  return { promise, resolve }
}

async function prepare() {
  fireEvent.click(screen.getByRole('button', { name: '브라우저 화면 조사' }))
  fireEvent.change(await screen.findByLabelText('화면 URL'), { target: { value: preview.scope.url } })
  fireEvent.change(screen.getByLabelText('허용 GET 경로'), { target: { value: '/app\n/api/status' } })
  await waitFor(() => expect(screen.getByRole('button', { name: '접근 범위 확인' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '접근 범위 확인' }))
  await screen.findByRole('button', { name: '이 범위로 브라우저 조사 시작' })
}

it('does nothing until opened and does not capture until exact scope is approved', async () => {
  render(<BrowserInvestigationPanel {...props} />)
  expect(api).not.toHaveBeenCalled()
  await prepare()
  expect(api.mock.calls.filter(([path, options]) => path.endsWith('/jobs') && options?.method === 'POST')).toHaveLength(0)
  expect(screen.getByText(/AI 호출 0회/)).toBeVisible()
})

it('shows rendered observations as escaped unverified text', async () => {
  const view = render(<BrowserInvestigationPanel {...props} />)
  await prepare()
  fireEvent.click(screen.getByRole('button', { name: '이 범위로 브라우저 조사 시작' }))
  expect(await screen.findByText(job.result.text)).toBeVisible()
  expect(view.container.querySelector('script')).toBeNull()
  expect(screen.getByText('브라우저 관찰 · 검증된 답변이 아님')).toBeVisible()
  const call = api.mock.calls.find(([path, options]) => path.endsWith('/jobs') && options?.method === 'POST')!
  expect(JSON.parse(call[1]!.body as string)).toEqual({ requestId: expect.any(String), bundleId: 'bundle', digest: 'digest', approved: true })
})

it('keeps request ID after uncertain submission and never auto retries', async () => {
  const original = api.getMockImplementation()!
  let posts = 0
  api.mockImplementation(async (path, options) => {
    if (path.endsWith('/jobs') && options?.method === 'POST' && ++posts === 1) throw new Error('Connection lost')
    return original(path, options)
  })
  render(<BrowserInvestigationPanel {...props} />)
  await prepare()
  fireEvent.click(screen.getByRole('button', { name: '이 범위로 브라우저 조사 시작' }))
  await screen.findByText('Connection lost')
  fireEvent.click(screen.getByRole('button', { name: '접수 상태 확인' }))
  await screen.findByText(job.result.text)
  const submissions = api.mock.calls.filter(([path, options]) => path.endsWith('/jobs') && options?.method === 'POST')
  expect(submissions).toHaveLength(2)
  expect(submissions[0][1]!.body).toEqual(submissions[1][1]!.body)
})

it('shows setup handoff without listing jobs or starting a browser', async () => {
  api.mockResolvedValue({ status: 'SETUP_REQUIRED', message: 'Install isolated browser' })
  render(<BrowserInvestigationPanel {...props} />)
  fireEvent.click(screen.getByRole('button', { name: '브라우저 화면 조사' }))
  expect(await screen.findByText('Install isolated browser')).toBeVisible()
  expect(api).toHaveBeenCalledTimes(1)
  expect(screen.queryByLabelText('화면 URL')).toBeNull()
})

it('adds a candidate only to a local draft and requires a fresh preview and consent', async () => {
  const candidateJob = { ...job, result: { ...job.result, observations: [
    ...job.result.observations, { method: 'GET', path: '/api/metrics', status: null, blocked: true, candidatePath: '/api/metrics' },
  ] } }
  api.mockImplementation(async (path, options) => path.endsWith('/capability') ? { status: 'AVAILABLE', message: 'Isolated' }
    : path.endsWith('/preview') ? { ...preview, digest: 'fresh-digest', scope: { ...preview.scope, allowedGetPaths: ['/app', '/api/status', '/api/metrics'] } }
      : options?.method === 'POST' ? candidateJob : [candidateJob])
  render(<BrowserInvestigationPanel {...props} />)
  fireEvent.click(screen.getByRole('button', { name: '브라우저 화면 조사' }))
  const candidate = await screen.findByRole('button', { name: '/api/metrics 새 범위 초안에 추가' })
  const calls = api.mock.calls.length
  fireEvent.click(candidate)
  expect(api).toHaveBeenCalledTimes(calls)
  expect(screen.getByLabelText('화면 URL')).toHaveValue(preview.scope.url)
  expect(screen.getByLabelText('허용 GET 경로')).toHaveValue('/app\n/api/status\n/api/metrics')
  fireEvent.click(screen.getByRole('button', { name: '접근 범위 확인' }))
  await screen.findByRole('button', { name: '이 범위로 브라우저 조사 시작' })
  expect(api.mock.calls.filter(([path, options]) => path.endsWith('/jobs') && options?.method === 'POST')).toHaveLength(0)
  fireEvent.click(screen.getByRole('button', { name: '이 범위로 브라우저 조사 시작' }))
  await waitFor(() => expect(api.mock.calls.filter(([path, options]) => path.endsWith('/jobs') && options?.method === 'POST')).toHaveLength(1))
  const post = api.mock.calls.find(([path, options]) => path.endsWith('/jobs') && options?.method === 'POST')!
  expect(JSON.parse(post[1]!.body as string).digest).toBe('fresh-digest')
})

it('never offers mutation, external, encoded or traversal candidates', async () => {
  const observations = [
    { method: 'POST', path: '/api/mutate', candidatePath: '/api/mutate' },
    { method: 'GET', path: 'https://outside.example/api', candidatePath: 'https://outside.example/api' },
    { method: 'GET', path: '/api/%2fsecret', candidatePath: '/api/%2fsecret' },
    { method: 'GET', path: '/api/../secret', candidatePath: '/api/../secret' },
    { method: 'GET', path: '/api/query?token=secret', candidatePath: '/api/query?token=secret' },
  ].map(value => ({ ...value, status: null, blocked: true }))
  api.mockImplementation(async path => path.endsWith('/capability') ? { status: 'AVAILABLE', message: 'Isolated' }
    : [{ ...job, result: { ...job.result, observations } }])
  render(<BrowserInvestigationPanel {...props} />)
  fireEvent.click(screen.getByRole('button', { name: '브라우저 화면 조사' }))
  await screen.findByText(job.result.text)
  expect(screen.queryByRole('button', { name: /새 범위 초안에 추가/ })).toBeNull()
})

it('bounds each poll to ten seconds and never overlaps even if abort is ignored', async () => {
  vi.useFakeTimers()
  const pending = deferredJob()
  let signal: AbortSignal | undefined
  let polls = 0
  api.mockImplementation(async (path, options) => {
    if (path.endsWith('/capability')) return { status: 'AVAILABLE', message: 'fixture' }
    if (path.endsWith('/jobs/job')) { polls++; signal = options?.signal as AbortSignal; return pending.promise }
    return [{ ...job, status: 'RUNNING' }]
  })
  render(<BrowserInvestigationPanel {...props} />)
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '브라우저 화면 조사' })) })
  await act(async () => { await vi.advanceTimersByTimeAsync(1500) })
  expect(polls).toBe(1)
  await act(async () => { await vi.advanceTimersByTimeAsync(9999) })
  expect(signal?.aborted).toBe(false)
  expect(polls).toBe(1)
  await act(async () => { await vi.advanceTimersByTimeAsync(1) })
  expect(signal?.aborted).toBe(true)
  expect(screen.getByText(/조사 상태 요청 시간이 초과/)).toBeVisible()
  await act(async () => { await vi.advanceTimersByTimeAsync(60000) })
  expect(polls).toBe(1)
  await act(async () => { pending.resolve(job) })
  expect(screen.queryByText('브라우저 조사 완료')).toBeNull()
})

it.each(['unmount', 'workspace', 'bundle', 'collapse'])('aborts polling and rejects late results after %s', async change => {
  vi.useFakeTimers()
  const pending = deferredJob()
  let signal: AbortSignal | undefined
  let polls = 0
  api.mockImplementation(async (path, options) => {
    if (path.endsWith('/capability')) return { status: 'AVAILABLE', message: 'fixture' }
    if (path.endsWith('/jobs/job')) { polls++; signal = options?.signal as AbortSignal; return pending.promise }
    return [{ ...job, status: 'RUNNING' }]
  })
  const view = render(<BrowserInvestigationPanel {...props} />)
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '브라우저 화면 조사' })) })
  await act(async () => { await vi.advanceTimersByTimeAsync(1500) })
  expect(polls).toBe(1)
  if (change === 'unmount') view.unmount()
  else if (change === 'collapse') fireEvent.click(screen.getByRole('button', { name: '접기' }))
  else view.rerender(<BrowserInvestigationPanel {...props}
    workspaceId={change === 'workspace' ? 'other' : props.workspaceId}
    bundleId={change === 'bundle' ? 'other' : props.bundleId} />)
  expect(signal?.aborted).toBe(true)
  await act(async () => { pending.resolve(job); await vi.advanceTimersByTimeAsync(20000) })
  expect(polls).toBe(1)
  expect(screen.queryByText('브라우저 조사 완료')).toBeNull()
  expect(vi.getTimerCount()).toBe(0)
})

it('rejects pre-cancel polls and never regresses cancelling or terminal state', async () => {
  vi.useFakeTimers()
  const pendingPoll = deferredJob()
  const pendingCancel = deferredJob()
  let polls = 0
  api.mockImplementation(async path => {
    if (path.endsWith('/capability')) return { status: 'AVAILABLE', message: 'fixture' }
    if (path.endsWith('/cancel')) return pendingCancel.promise
    if (path.endsWith('/jobs/job')) {
      polls++
      if (polls === 1) return pendingPoll.promise
      return { ...job, status: polls === 2 ? 'CANCELLING' : polls === 3 ? 'RUNNING' : 'CANCELLED' }
    }
    return [{ ...job, status: 'RUNNING' }]
  })
  render(<BrowserInvestigationPanel {...props} />)
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '브라우저 화면 조사' })) })
  await act(async () => { await vi.advanceTimersByTimeAsync(1500) })
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '조사 취소' })) })
  await act(async () => { pendingPoll.resolve(job) })
  expect(screen.queryByText('브라우저 조사 완료')).toBeNull()
  await act(async () => { await vi.advanceTimersByTimeAsync(1500) })
  expect(screen.getByText('브라우저 조사 취소 중…')).toBeVisible()
  await act(async () => { await vi.advanceTimersByTimeAsync(1500) })
  expect(screen.getByText('브라우저 조사 취소 중…')).toBeVisible()
  await act(async () => { await vi.advanceTimersByTimeAsync(1500) })
  expect(screen.getByText('조사 취소됨')).toBeVisible()
  await act(async () => { pendingCancel.resolve({ ...job, status: 'CANCELLING' }); await vi.advanceTimersByTimeAsync(15000) })
  expect(screen.getByText('조사 취소됨')).toBeVisible()
  expect(polls).toBe(4)
  expect(vi.getTimerCount()).toBe(0)
})

it('retries a timed-out poll only after that request has settled', async () => {
  vi.useFakeTimers()
  let polls = 0
  api.mockImplementation(async (path, options) => {
    if (path.endsWith('/capability')) return { status: 'AVAILABLE', message: 'fixture' }
    if (path.endsWith('/jobs/job')) {
      polls++
      if (polls > 1) return job
      return new Promise((_, reject) => options?.signal?.addEventListener('abort', () => reject(new Error('aborted')), { once: true }))
    }
    return [{ ...job, status: 'RUNNING' }]
  })
  render(<BrowserInvestigationPanel {...props} />)
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '브라우저 화면 조사' })) })
  await act(async () => { await vi.advanceTimersByTimeAsync(11500) })
  expect(polls).toBe(1)
  await act(async () => { await vi.advanceTimersByTimeAsync(1499) })
  expect(polls).toBe(1)
  await act(async () => { await vi.advanceTimersByTimeAsync(1) })
  expect(polls).toBe(2)
  expect(screen.getByText('브라우저 조사 완료')).toBeVisible()
})
