import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { request } from './api'
import SlackReplyPanel from './SlackReplyPanel'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const base = '/slack/workspaces/one/replies'
const body = 'Synthetic historical reply & <img src="https://example.invalid/no-request">\n  unchanged'
const receipt = { id: 'reply-one', workspaceId: 'one', inboxItemId: 'item-one', body, digest: 'a'.repeat(64), scopeHash: 'b'.repeat(64),
  scope: { workspaceId: 'one', inboxItemId: 'item-one', teamId: 'TTEAM', channelId: 'CCHANNEL', threadTimestamp: '1800000000.1', botUserId: 'UBOT' },
  status: 'PREVIEW', dispatchCount: 0, slackMessageTs: null, failureCode: null, expiresAt: '2020-01-01T00:00:00Z' }
const props = { workspaceId: 'one', inboxItemId: 'item-one' }
beforeEach(() => { vi.clearAllMocks(); api.mockReset(); api.mockResolvedValue([receipt]) })
afterEach(() => { vi.useRealTimers() })
async function open() { await userEvent.click(screen.getByRole('button', { name: 'Slack 과거 영수증 조회' })) }
async function select() { await userEvent.click(await screen.findByRole('button', { name: `과거 답장 내용 확인 ${receipt.id}` })) }
function readOnlyRequests() {
  expect(api.mock.calls.every(([, options]) => options?.method === 'GET' && options.body === undefined)).toBe(true)
  expect(api.mock.calls.some(([path]) => /\/(preview|execute|cancel|acknowledge-uncertain)(?:\?|$)/.test(path))).toBe(false)
}

it.each(['PREVIEW', 'SENDING', 'SENT', 'FAILED', 'UNCERTAIN', 'CANCELLED', 'EXPIRED'])('preserves %s historical content with GET-only controls', async status => {
  const saved = { ...receipt, status, dispatchCount: ['SENDING', 'SENT', 'FAILED', 'UNCERTAIN'].includes(status) ? 1 : 0,
    slackMessageTs: status === 'SENT' ? '1800000001.1' : null }
  api.mockResolvedValueOnce([saved]).mockResolvedValueOnce(saved)
  const view = render(<SlackReplyPanel {...props} />)
  expect(api).not.toHaveBeenCalled()
  await open(); await select()
  expect(screen.getByLabelText('저장된 과거 답장 내용').textContent).toBe(body)
  expect(view.container.querySelector('img, a, textarea, input')).toBeNull()
  expect(screen.queryByText(/chat:write/)).not.toBeInTheDocument()
  expect(screen.getAllByRole('button').map(button => button.textContent)).toEqual([
    'Slack 과거 영수증 조회', `과거 답장 내용 확인 ${receipt.id}`, '영수증 다시 조회',
  ])
  await userEvent.click(screen.getByRole('button', { name: '영수증 다시 조회' }))
  expect(api).toHaveBeenLastCalledWith(`${base}/${receipt.id}`, expect.objectContaining({ method: 'GET' }))
  expect(screen.getByLabelText('저장된 과거 답장 내용').textContent).toBe(body)
  readOnlyRequests()
})

it('wraps long historical text without altering or truncating it', async () => {
  const longBody = 'x'.repeat(3990) + '\n  end'
  api.mockResolvedValueOnce([{ ...receipt, body: longBody }])
  const style = document.createElement('style')
  style.textContent = readFileSync('src/SlackReplyPanel.css', 'utf8'); document.head.append(style)
  try {
    render(<SlackReplyPanel {...props} />); await open(); await select()
    const content = screen.getByLabelText('저장된 과거 답장 내용')
    expect(content.textContent).toBe(longBody)
    const computed = getComputedStyle(content)
    expect(computed.whiteSpace).toBe('pre-wrap'); expect(computed.overflowWrap).toBe('anywhere')
    expect(computed.maxWidth).toBe('100%'); expect(computed.maxHeight).toBe('320px'); expect(computed.overflow).toBe('auto')
  } finally { style.remove() }
})

it.each([
  { workspaceId: 'other' }, { inboxItemId: 'other' }, { dispatchCount: undefined }, { dispatchCount: 2 },
  { dispatchCount: 1 }, { scope: { ...receipt.scope, botUserId: 'CBOT' } }, { status: 'UNKNOWN' },
  { status: 'SENT', dispatchCount: 1, slackMessageTs: 'invalid' }, { digest: 'bad' },
])('rejects malformed or cross-context history %j', async patch => {
  api.mockResolvedValueOnce([{ ...receipt, ...patch }])
  render(<SlackReplyPanel {...props} />); await open()
  expect(await screen.findByRole('alert')).toHaveTextContent('대상 또는 형식')
  expect(screen.queryByLabelText('저장된 과거 답장 내용')).not.toBeInTheDocument()
  readOnlyRequests()
})

it('rejects duplicate history IDs', async () => {
  api.mockResolvedValueOnce([receipt, receipt])
  render(<SlackReplyPanel {...props} />); await open()
  expect(await screen.findByRole('alert')).toHaveTextContent('중복')
  readOnlyRequests()
})

it('rejects a detail for another receipt without replacing historical text', async () => {
  render(<SlackReplyPanel {...props} />); await open(); await select()
  api.mockResolvedValueOnce({ ...receipt, id: 'other', body: 'wrong' })
  await userEvent.click(screen.getByRole('button', { name: '영수증 다시 조회' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('일치하지')
  expect(screen.getByLabelText('저장된 과거 답장 내용').textContent).toBe(body)
  readOnlyRequests()
})

it('does not load while disabled or automatically load empty history', async () => {
  api.mockResolvedValue([])
  const view = render(<SlackReplyPanel {...props} disabled />)
  fireEvent.click(screen.getByRole('button')); expect(api).not.toHaveBeenCalled()
  view.rerender(<SlackReplyPanel {...props} />)
  await open(); expect(await screen.findByText('저장된 과거 답장 영수증이 없습니다.')).toBeVisible()
  readOnlyRequests()
})

it.each([{ workspaceId: 'two', inboxItemId: 'item-one' }, { workspaceId: 'one', inboxItemId: 'item-two' }])('aborts and ignores old history on context switch %j', async next => {
  let resolve!: (value: unknown) => void
  api.mockImplementationOnce(() => new Promise(done => { resolve = done }))
  const busy = vi.fn()
  const view = render(<SlackReplyPanel {...props} onBusyChange={busy} />)
  await open()
  const signal = api.mock.calls[0][1]!.signal!
  view.rerender(<SlackReplyPanel {...next} onBusyChange={busy} />)
  expect(signal.aborted).toBe(true)
  await act(async () => resolve([receipt]))
  expect(screen.queryByRole('button', { name: `과거 답장 내용 확인 ${receipt.id}` })).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(1)
  expect(busy).toHaveBeenLastCalledWith(false)
})

it('unmount aborts reads and releases busy without any mutation', async () => {
  api.mockImplementationOnce(() => new Promise(() => {}))
  const busy = vi.fn()
  const view = render(<SlackReplyPanel {...props} onBusyChange={busy} />)
  await open(); const signal = api.mock.calls[0][1]!.signal!
  view.unmount(); expect(signal.aborted).toBe(true)
  await waitFor(() => expect(busy).toHaveBeenLastCalledWith(false))
  readOnlyRequests()
})

it('times out even when transport ignores abort; retry ignores the late response', async () => {
  let resolve!: (value: unknown) => void
  api.mockImplementationOnce(() => new Promise(done => { resolve = done }))
  const busy = vi.fn()
  render(<SlackReplyPanel {...props} onBusyChange={busy} />)
  vi.useFakeTimers()
  fireEvent.click(screen.getByRole('button'))
  const signal = api.mock.calls[0][1]!.signal!
  await act(async () => { vi.advanceTimersByTime(20000) })
  expect(signal.aborted).toBe(true)
  expect(screen.getByRole('alert')).toHaveTextContent('시간이 초과')
  expect(busy).toHaveBeenLastCalledWith(false)
  api.mockResolvedValueOnce([])
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Slack 과거 영수증 조회' })))
  await act(async () => resolve([receipt]))
  expect(screen.queryByRole('button', { name: `과거 답장 내용 확인 ${receipt.id}` })).not.toBeInTheDocument()
  expect(screen.getByText('저장된 과거 답장 영수증이 없습니다.')).toBeVisible()
  readOnlyRequests()
})
