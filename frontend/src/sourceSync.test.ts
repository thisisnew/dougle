import { afterEach, expect, it, vi } from 'vitest'
import { syncSource } from './sourceSync'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
afterEach(() => { api.mockReset(); vi.useRealTimers() })

it('submits once and polls a long running job without repeating collection', async () => {
  vi.useFakeTimers()
  const source = { id: 'one', status: 'READY' }
  api.mockResolvedValueOnce({ id: 'job', state: 'RUNNING' })
    .mockResolvedValueOnce({ id: 'job', state: 'RUNNING' })
    .mockResolvedValueOnce({ id: 'job', state: 'COMPLETED', source })
  const result = syncSource('/workspaces/a/sources/one', new AbortController().signal)
  await vi.advanceTimersByTimeAsync(1000)
  expect(await result).toEqual(source)
  expect(api.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)
  expect(api.mock.calls[1][0]).toBe('/workspaces/a/sources/one/sync-jobs/job')
})

it('does not restart collection after a lost polling response', async () => {
  vi.useFakeTimers()
  api.mockResolvedValueOnce({ id: 'job', state: 'RUNNING' }).mockRejectedValueOnce(new Error('connection lost'))
  const result = expect(syncSource('/workspaces/a/sources/one', new AbortController().signal)).rejects.toThrow('connection lost')
  await vi.advanceTimersByTimeAsync(500)
  await result
  expect(api.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)
})

it('stops polling on navigation cancellation', async () => {
  vi.useFakeTimers()
  api.mockResolvedValueOnce({ id: 'job', state: 'RUNNING' })
  const controller = new AbortController()
  const result = expect(syncSource('/workspaces/a/sources/one', controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
  controller.abort()
  await result
  await vi.advanceTimersByTimeAsync(2000)
  expect(api).toHaveBeenCalledOnce()
})

it('reports server failure without accepting a missing source', async () => {
  api.mockResolvedValue({ id: 'job', state: 'FAILED', error: '파일 읽기 실패' })
  await expect(syncSource('/workspaces/a/sources/one', new AbortController().signal)).rejects.toThrow('파일 읽기 실패')
})
