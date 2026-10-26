import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { request } from './api'
import SlackInboxIndicator from './SlackInboxIndicator'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
beforeEach(() => { vi.clearAllMocks(); vi.useFakeTimers() })
afterEach(() => { vi.useRealTimers() })
async function flush() { await act(async () => { await Promise.resolve() }) }

it('polls only local workspace counts and renders a noninteractive connected badge', async () => {
  api.mockResolvedValue({ state: 'CONNECTED', count: 2 })
  const view = render(<SlackInboxIndicator workspaceId="synthetic one" />)
  await flush()
  expect(screen.getByLabelText('Slack 수신함 2개').tagName).toBe('SPAN')
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledWith('/slack/workspaces/synthetic%20one/inbox-count', expect.objectContaining({ signal: expect.any(AbortSignal) }))
  await act(async () => { await vi.advanceTimersByTimeAsync(15000) })
  expect(api).toHaveBeenCalledTimes(2)
  expect(api.mock.calls.every(([path, options]) => path.endsWith('/inbox-count') && !options?.method)).toBe(true)
  view.unmount()
  await act(async () => { await vi.advanceTimersByTimeAsync(30000) })
  expect(api).toHaveBeenCalledTimes(2)
})

it.each(['NOT_CONFIGURED', 'DISCONNECTED', 'RECONNECTING', 'PERMISSION_DENIED', 'AUTH_REQUIRED'])('hides private counts in %s state', async state => {
  api.mockResolvedValue({ state, count: 2 })
  const { container } = render(<SlackInboxIndicator workspaceId="one" />)
  await flush()
  expect(container).toBeEmptyDOMElement()
})

it('hides zero counts and does not poll without a workspace', async () => {
  api.mockResolvedValue({ state: 'CONNECTED', count: 0 })
  const view = render(<SlackInboxIndicator />)
  expect(api).not.toHaveBeenCalled()
  view.rerender(<SlackInboxIndicator workspaceId="one" />)
  await flush()
  expect(view.container).toBeEmptyDOMElement()
})

it('clears stale badge after a failed poll and recovers without overlapping requests', async () => {
  api.mockResolvedValueOnce({ state: 'CONNECTED', count: 2 }).mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValue({ state: 'CONNECTED', count: 3 })
  const view = render(<SlackInboxIndicator workspaceId="one" />)
  await flush()
  await act(async () => { await vi.advanceTimersByTimeAsync(15000) })
  expect(view.container).toBeEmptyDOMElement()
  await act(async () => { await vi.advanceTimersByTimeAsync(15000) })
  expect(screen.getByLabelText('Slack 수신함 3개')).toBeInTheDocument()
})

it('aborts old workspace requests and ignores late responses', async () => {
  let resolve: (value: unknown) => void = () => {}
  api.mockImplementationOnce(() => new Promise(done => { resolve = done })).mockResolvedValue({ state: 'CONNECTED', count: 1 })
  const view = render(<SlackInboxIndicator workspaceId="one" />)
  const signal = api.mock.calls[0][1]?.signal
  view.rerender(<SlackInboxIndicator workspaceId="two" />)
  await flush()
  expect(signal?.aborted).toBe(true)
  await act(async () => { resolve({ state: 'CONNECTED', count: 99 }) })
  expect(screen.getByLabelText('Slack 수신함 1개')).toBeInTheDocument()
  expect(screen.queryByLabelText('Slack 수신함 99개')).not.toBeInTheDocument()
})
