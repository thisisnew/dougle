import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ApiError, request } from './api'
import { readConversationPlan, sendConversationPlan, type PlanSubmission } from './sendConversationPlan'

vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(), request: vi.fn() }))
const api = vi.mocked(request)
const preview = { bundleId: 'bundle', provider: 'CODEX', requestedModel: 'model', digest: 'digest' }
const job = { ...preview, id: '11111111-1111-4111-8111-111111111111', workspaceId: 'workspace', status: 'SUCCEEDED', decision: 'STOP' }
beforeEach(() => { api.mockReset(); vi.restoreAllMocks(); vi.spyOn(crypto, 'randomUUID').mockReturnValue('11111111-1111-4111-8111-111111111111') })
afterEach(() => vi.useRealTimers())
const run = (remember = vi.fn(), existing?: PlanSubmission, signal = new AbortController().signal) => sendConversationPlan('workspace', 'bundle', 'CODEX', 'model', signal, remember, existing)

it('previews and submits exactly one bounded plan with pinned provider and model', async () => {
  api.mockResolvedValueOnce(preview).mockResolvedValueOnce(job)
  const remember = vi.fn()
  expect(await run(remember)).toEqual(job)
  expect(api.mock.calls.map(([path]) => path)).toEqual(['/workspaces/workspace/investigation-plans/preview', '/workspaces/workspace/investigation-plans/jobs'])
  const body = JSON.parse(api.mock.calls[1][1]!.body as string)
  expect(body).toEqual({ requestId: expect.any(String), ...preview, approved: true })
  expect(remember).toHaveBeenCalledExactlyOnceWith(body)
  expect(remember.mock.invocationCallOrder[0]).toBeLessThan(api.mock.invocationCallOrder[1])
})

it('retries uncertain acceptance only explicitly with the exact remembered request', async () => {
  api.mockResolvedValueOnce(preview).mockRejectedValueOnce(new Error('lost response')).mockRejectedValueOnce(new ApiError('not found', 404)).mockResolvedValueOnce(job)
  const remember = vi.fn()
  await expect(run(remember)).rejects.toThrow('lost response')
  expect(api).toHaveBeenCalledTimes(2)
  await run(remember, remember.mock.calls[0][0])
  expect(api).toHaveBeenCalledTimes(4)
  expect(api.mock.calls[2][0]).toBe(`/workspaces/workspace/investigation-plans/jobs/${job.id}`)
  expect(api.mock.calls[2][1]?.method).toBeUndefined()
  expect(api.mock.calls[1][1]!.body).toEqual(api.mock.calls[3][1]!.body)
})

it('polls the accepted job without another POST', async () => {
  vi.useFakeTimers()
  api.mockResolvedValueOnce(preview).mockResolvedValueOnce({ ...job, status: 'RUNNING' }).mockResolvedValueOnce(job)
  const pending = run()
  await vi.advanceTimersByTimeAsync(1000)
  expect(await pending).toEqual(job)
  expect(api.mock.calls[2]).toEqual([`/workspaces/workspace/investigation-plans/jobs/${job.id}`, expect.objectContaining({ signal: expect.any(AbortSignal) })])
  expect(api.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(2)
})

it.each(['bundleId', 'provider', 'requestedModel'])('rejects foreign preview %s before plan submission', async field => {
  api.mockResolvedValueOnce({ ...preview, [field]: 'other' })
  await expect(run()).rejects.toThrow('조사 조건')
  expect(api).toHaveBeenCalledTimes(1)
})

it('rejects a mismatched job and cannot dispatch an answer', async () => {
  api.mockResolvedValueOnce(preview).mockResolvedValueOnce({ ...job, workspaceId: 'other' })
  await expect(run()).rejects.toThrow('조사 대상')
  expect(api).toHaveBeenCalledTimes(2)
})

it('has a hard deadline despite abort-ignoring transport and ignores late preview', async () => {
  vi.useFakeTimers()
  let resolve!: (value: unknown) => void
  api.mockImplementation(() => new Promise(done => { resolve = done }))
  const remember = vi.fn()
  const rejected = expect(run(remember)).rejects.toThrow('조사 접수 상태')
  await vi.advanceTimersByTimeAsync(120_000)
  await rejected
  resolve(preview)
  await Promise.resolve(); await Promise.resolve()
  expect(remember).not.toHaveBeenCalled()
  expect(api).toHaveBeenCalledTimes(1)
})

it('does not silently reuse a plan approval for another model', async () => {
  await expect(run(vi.fn(), { ...preview, requestId: 'original-request', requestedModel: 'different', approved: true })).rejects.toThrow('이전 제공자와 모델')
  expect(api).not.toHaveBeenCalled()
})

it.each(['id', 'workspaceId', 'bundleId', 'provider', 'requestedModel', 'digest', 'decision'])('rejects a mismatched or invalid job %s', async field => {
  api.mockResolvedValueOnce(preview).mockResolvedValueOnce({ ...job, [field]: 'other' })
  await expect(run()).rejects.toThrow()
  expect(api).toHaveBeenCalledTimes(2)
})

it('recovers a late accepted plan with GET and never repeats the POST', async () => {
  api.mockResolvedValueOnce(job)
  const existing = { ...preview, requestId: job.id, approved: true as const }
  expect(await run(vi.fn(), existing)).toEqual(job)
  expect(api).toHaveBeenCalledExactlyOnceWith(`/workspaces/workspace/investigation-plans/jobs/${job.id}`, expect.objectContaining({ signal: expect.any(AbortSignal) }))
})

it('restores the latest scoped plan using only GET', async () => {
  api.mockResolvedValueOnce([job])
  expect(await readConversationPlan('workspace', 'bundle', new AbortController().signal)).toEqual(job)
  expect(api).toHaveBeenCalledExactlyOnceWith('/workspaces/workspace/investigation-plans/jobs?bundleId=bundle', expect.objectContaining({ signal: expect.any(AbortSignal) }))
})

it('fails closed on foreign history jobs without planning again', async () => {
  api.mockResolvedValueOnce([{ ...job, workspaceId: 'other' }])
  await expect(readConversationPlan('workspace', 'bundle', new AbortController().signal)).rejects.toThrow('조사 접수 상태')
  expect(api).toHaveBeenCalledTimes(1)
})
