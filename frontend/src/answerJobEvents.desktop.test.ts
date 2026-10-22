import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { observeAnswerJob } from './answerJobEvents'

const evidence = { id: 'bundle', workspaceId: 'workspace', digest: 'hash' }
const job = { id: 'job', workspaceId: 'workspace', bundleId: 'bundle', approvalId: 'approval', provider: 'CODEX', requestedModel: '', status: 'RUNNING', answer: null, error: null }
const partial = { workspaceId: 'workspace', bundleId: 'bundle', jobId: 'job', text: '임시 글', unverified: true }
const event = (name: string, value: unknown) => `event: ${name}\r\ndata: ${JSON.stringify(value)}\r\n\r\n`
let stop: (() => void) | undefined
beforeEach(() => { vi.useFakeTimers(); vi.stubGlobal('__FORGE_DESKTOP_SESSION__', 'a'.repeat(64)) })
afterEach(() => { stop?.(); vi.useRealTimers(); vi.unstubAllGlobals() })
function start() {
  const callbacks = { job: vi.fn(), state: vi.fn(), error: vi.fn(), partial: vi.fn() }
  stop = observeAnswerJob('workspace', 'bundle', 'job', callbacks)
  return callbacks
}
function stream() {
  let control!: ReadableStreamDefaultController<Uint8Array>
  const cancel = vi.fn()
  const body = new ReadableStream<Uint8Array>({ start(value) { control = value }, cancel })
  return { control, cancel, response: new Response(body, { headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' } }) }
}
const settle = () => vi.advanceTimersByTimeAsync(0)

it('handles byte fragmentation, UTF-8, CRLF, comments, multiline data and terminal cancellation', async () => {
  const connection = stream()
  const fetcher = vi.fn().mockResolvedValue(connection.response)
  vi.stubGlobal('fetch', fetcher)
  const callbacks = start()
  const text = ': heartbeat\r\n\r\nevent: evidence\r\ndata: {"id":"bundle",\r\ndata: "workspaceId":"workspace","digest":"hash"}\r\n\r\n'
    + event('job', job) + event('partial', partial) + event('job', { ...job, status: 'SUCCEEDED', answer: '최종 [R1]' })
  for (const byte of new TextEncoder().encode(text)) connection.control.enqueue(new Uint8Array([byte]))
  await settle()
  expect(callbacks.job).toHaveBeenCalledTimes(2)
  expect(callbacks.partial).toHaveBeenCalledWith('임시 글')
  expect(callbacks.partial).toHaveBeenLastCalledWith('')
  expect(callbacks.error).not.toHaveBeenCalled()
  expect(connection.cancel).toHaveBeenCalledOnce()
  expect(fetcher.mock.calls[0][1].signal.aborted).toBe(true)
  expect(fetcher.mock.calls[0][1].headers.get('X-Forge-Session')).toBe('a'.repeat(64))
  await vi.advanceTimersByTimeAsync(70000)
  expect(fetcher).toHaveBeenCalledOnce()
})

it.each(['http', 'mime', 'eof', 'network', 'read'])('bounds %s failures to three GET-only reconnects', async failure => {
  const fetcher = vi.fn().mockImplementation(() => {
    if (failure === 'network') return Promise.reject(new Error('network'))
    const connection = stream()
    if (failure === 'read') connection.control.error(new Error('read'))
    if (failure === 'eof') connection.control.close()
    return Promise.resolve(failure === 'http' ? new Response(null, { status: 403 })
      : failure === 'mime' ? new Response('html') : connection.response)
  })
  vi.stubGlobal('fetch', fetcher)
  const callbacks = start()
  await settle()
  for (const delay of [1000, 2000, 4000]) await vi.advanceTimersByTimeAsync(delay)
  expect(callbacks.error).toHaveBeenCalledWith(expect.stringContaining('3회'))
  await vi.advanceTimersByTimeAsync(70000)
  expect(fetcher).toHaveBeenCalledTimes(4)
  for (const [url, options] of fetcher.mock.calls) {
    expect(url).toBe('/api/workspaces/workspace/answer-jobs/job/events')
    expect(options.method ?? 'GET').toBe('GET')
    expect(options.body).toBeUndefined()
  }
})

it.each([event('job', job), event('evidence', { ...evidence, workspaceId: 'other' }), 'event: evidence\ndata: invalid\n\n'])('fails closed on invalid protocol without retries', async text => {
  const connection = stream()
  const fetcher = vi.fn().mockResolvedValue(connection.response)
  vi.stubGlobal('fetch', fetcher)
  const callbacks = start()
  connection.control.enqueue(new TextEncoder().encode(text))
  await settle()
  expect(callbacks.error).toHaveBeenCalledOnce()
  expect(callbacks.job).not.toHaveBeenCalled()
  await vi.advanceTimersByTimeAsync(70000)
  expect(fetcher).toHaveBeenCalledOnce()
})

it('revalidates evidence after rotation and clears partial output', async () => {
  const first = stream(); const second = stream()
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(first.response).mockResolvedValueOnce(second.response))
  const callbacks = start()
  first.control.enqueue(new TextEncoder().encode(event('evidence', evidence) + event('job', job) + event('partial', partial) + event('done', { reason: 'reconnect' })))
  await settle()
  expect(callbacks.partial).toHaveBeenLastCalledWith('')
  await vi.advanceTimersByTimeAsync(1000)
  second.control.enqueue(new TextEncoder().encode(event('job', job)))
  await settle()
  expect(callbacks.error).toHaveBeenCalledOnce()
  expect(callbacks.job).toHaveBeenCalledOnce()
})

it('aborts a pending connection and discards a late response', async () => {
  const connection = stream()
  let resolve!: (response: Response) => void
  const fetcher = vi.fn().mockReturnValue(new Promise<Response>(done => { resolve = done }))
  vi.stubGlobal('fetch', fetcher)
  const callbacks = start()
  stop?.()
  expect(fetcher.mock.calls[0][1].signal.aborted).toBe(true)
  resolve(connection.response)
  await settle()
  expect(connection.cancel).toHaveBeenCalledOnce()
  expect(callbacks.job).not.toHaveBeenCalled()
  expect(callbacks.error).not.toHaveBeenCalled()
  await vi.advanceTimersByTimeAsync(70000)
  expect(fetcher).toHaveBeenCalledOnce()
})

it('aborts a silent reader on watchdog and cancels pending retry on stop', async () => {
  const connection = stream()
  const fetcher = vi.fn().mockResolvedValue(connection.response)
  vi.stubGlobal('fetch', fetcher)
  start()
  await settle()
  await vi.advanceTimersByTimeAsync(65000)
  expect(connection.cancel).toHaveBeenCalledOnce()
  stop?.()
  await vi.advanceTimersByTimeAsync(70000)
  expect(fetcher).toHaveBeenCalledOnce()
})
