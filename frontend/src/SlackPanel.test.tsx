import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { ApiError, request } from './api'
import SlackPanel from './SlackPanel'

vi.mock('./api', async importOriginal => ({ ...await importOriginal<typeof import('./api')>(), request: vi.fn() }))
const api = vi.mocked(request)
const initial: { state: string; teamId: string | null; inboxCount: number; ignoredEvents: number; sessionOnly: boolean } = {
  state: 'NOT_CONFIGURED', teamId: null, inboxCount: 0, ignoredEvents: 0, sessionOnly: true,
}
const event = { eventId: 'EvSYNTHETIC', teamId: 'TTEAM', channelId: 'CCHANNEL', userId: 'UHUMAN', timestamp: '1800000000.000001', text: 'Synthetic Strategy question' }
const item = { id: 'event-one', workspaceId: 'one', event, receivedAt: '2026-10-25T00:00:00Z', sentQuestion: null, sent: null }
let currentStatus = initial
let currentItems: unknown[] = []

beforeEach(() => {
  vi.clearAllMocks()
  currentStatus = initial
  currentItems = []
  api.mockImplementation(async path => {
    if (path === '/slack/status') return currentStatus
    if (path === '/slack/routes') return []
    if (path.endsWith('/inbox')) return currentItems
    throw new Error('Unexpected request')
  })
})

it('설정되지 않은 연결을 정직하게 표시하고 조회만 한다', async () => {
  render(<SlackPanel workspaceId="one" />)
  expect(await screen.findByText('연결 설정 필요')).toBeInTheDocument()
  expect(screen.queryByText('Slack 수신 연결됨')).not.toBeInTheDocument()
  expect(screen.getByText(/앱 등록·OAuth·회사 승인/)).toBeInTheDocument()
  expect(screen.getByText(/앱 종료 시 사라집니다/)).toBeInTheDocument()
  await userEvent.click(screen.getByText('고급 연결 설정 · 관리자 앱 준비 필요'))
  expect(screen.getByRole('button', { name: 'Slack 수신 연결' })).toBeDisabled()
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
})

it('토큰·명시적 동의가 있어야 연결하고 실패 후 토큰을 지운다', async () => {
  render(<SlackPanel workspaceId="one" />)
  await screen.findByText('연결 설정 필요')
  await userEvent.click(screen.getByText('고급 연결 설정 · 관리자 앱 준비 필요'))
  await userEvent.type(screen.getByLabelText('Slack 앱 토큰'), 'xapp-synthetic')
  await userEvent.type(screen.getByLabelText('Slack 봇 토큰'), 'xoxb-synthetic')
  expect(screen.getByRole('button', { name: 'Slack 수신 연결' })).toBeDisabled()
  await userEvent.click(screen.getByRole('checkbox'))
  api.mockRejectedValueOnce(new Error('Slack 인증을 확인하지 못했습니다.'))
  await userEvent.click(screen.getByRole('button', { name: 'Slack 수신 연결' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Slack 인증을 확인하지 못했습니다.')
  expect(api).toHaveBeenCalledWith('/slack/connection', expect.objectContaining({ method: 'POST', body: JSON.stringify({ appToken: 'xapp-synthetic', botToken: 'xoxb-synthetic', acknowledged: true }) }))
  expect(screen.getByLabelText('Slack 앱 토큰')).toHaveValue('')
  expect(screen.getByLabelText('Slack 봇 토큰')).toHaveValue('')
  expect(screen.queryByText('Slack 수신 연결됨')).not.toBeInTheDocument()
})

it('읽기 전용 정책을 표시하고 게시 권한이나 전송 UI를 제공하지 않는다', async () => {
  currentItems = [item]
  currentStatus = { ...initial, state: 'CONNECTED', teamId: 'TTEAM' }
  render(<SlackPanel workspaceId="one" />)
  await screen.findByText('Slack 수신 연결됨')
  await userEvent.click(screen.getByText('고급 연결 설정 · 관리자 앱 준비 필요'))
  expect(screen.queryByText(/chat:write/)).not.toBeInTheDocument()
  expect(screen.getByText(/Slack은 읽기 전용입니다/)).toHaveTextContent('로컬 자료에만 적용됩니다')
  expect(screen.queryByRole('button', { name: /게시|전송|답장.*작성/ })).not.toBeInTheDocument()
  expect(screen.getByText(/message.channels와 channels:history/)).toBeVisible()
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
  expect(api.mock.calls.some(([path]) => path.includes('/replies'))).toBe(false)
})

it('수신은 AI를 호출하지 않으며 명시적 한 번의 클릭으로 질문을 저장한다', async () => {
  currentItems = [item]
  const onConversationSaved = vi.fn()
  render(<SlackPanel workspaceId="one" onConversationSaved={onConversationSaved} />)
  await screen.findByText(event.text, { selector: 'pre' })
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
  expect(onConversationSaved).not.toHaveBeenCalled()
  const saved = { threadId: 'saved-thread', bundleId: 'saved-bundle' }
  api.mockResolvedValueOnce(saved)
  await userEvent.click(screen.getByRole('button', { name: '대화에 질문 저장' }))
  await waitFor(() => expect(onConversationSaved).toHaveBeenCalledExactlyOnceWith('one', 'saved-thread', 'saved-bundle'))
  const writes = api.mock.calls.filter(([, options]) => options?.method)
  expect(writes).toHaveLength(1)
  expect(writes[0]).toEqual(['/slack/workspaces/one/inbox/event-one/conversation', expect.objectContaining({ method: 'POST', body: JSON.stringify({ question: event.text }) })])
  expect(api.mock.calls.some(([path]) => /answer-jobs|answer-approval|answer-preview/.test(path))).toBe(false)
})

it('삭제된 대화 복구는 구조화된 충돌 뒤 명시적 동의와 한 번의 저장만 허용한다', async () => {
  currentItems = [item]
  const callback = vi.fn()
  render(<SlackPanel workspaceId="one" onConversationSaved={callback} />)
  await screen.findByText(event.text, { selector: 'pre' })
  api.mockRejectedValueOnce(new ApiError('Missing thread', 409, 'SLACK_THREAD_MISSING', 'old-thread'))
  await userEvent.click(screen.getByRole('button', { name: '대화에 질문 저장' }))
  const recover = await screen.findByRole('button', { name: '새 대화로 저장' })
  expect(recover).toBeDisabled()
  expect(api.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)
  expect(callback).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('checkbox', { name: /기존 대화는 삭제되었습니다/ }))
  let finish!: (value: unknown) => void
  api.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  fireEvent.click(recover); fireEvent.click(recover)
  const writes = api.mock.calls.filter(([, options]) => options?.method === 'POST')
  expect(writes).toHaveLength(2)
  expect(JSON.parse(writes[1][1]!.body as string)).toEqual({ question: event.text, startNewConversation: true, expectedThreadId: 'old-thread' })
  const sent = { threadId: 'new-thread', bundleId: 'new-bundle' }
  currentItems = [{ ...item, sent, sentQuestion: event.text }]
  await act(async () => finish(sent))
  expect(callback).toHaveBeenCalledExactlyOnceWith('one', 'new-thread', 'new-bundle')
  expect(api.mock.calls.some(([path]) => /answer-jobs|answer-approval|answer-preview|replies.*execute/.test(path))).toBe(false)
})

it.each([
  new ApiError('SLACK_THREAD_MISSING old-thread', 409),
  new ApiError('Busy thread', 409, 'THREAD_BUSY', 'old-thread'),
  new ApiError('Wrong status', 500, 'SLACK_THREAD_MISSING', 'old-thread'),
])('일반 오류나 메시지 문구만으로 복구를 제안하지 않는다: %s', async failure => {
  currentItems = [item]
  render(<SlackPanel workspaceId="one" />)
  await screen.findByText(event.text, { selector: 'pre' })
  api.mockRejectedValueOnce(failure)
  await userEvent.click(screen.getByRole('button', { name: '대화에 질문 저장' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(failure.message)
  expect(screen.queryByRole('button', { name: '새 대화로 저장' })).not.toBeInTheDocument()
})

it('복구 질문 편집은 동의를 해제하고 Workspace 전환은 이전 복구 상태를 버린다', async () => {
  currentItems = [item]
  const view = render(<SlackPanel workspaceId="one" />)
  await screen.findByText(event.text, { selector: 'pre' })
  api.mockRejectedValueOnce(new ApiError('Missing thread', 409, 'SLACK_THREAD_MISSING', 'old-thread'))
  await userEvent.click(screen.getByRole('button', { name: '대화에 질문 저장' }))
  await userEvent.click(screen.getByRole('checkbox', { name: /기존 대화는 삭제되었습니다/ }))
  fireEvent.change(screen.getByLabelText('대화에 보낼 질문'), { target: { value: 'Changed question' } })
  expect(screen.getByRole('button', { name: '새 대화로 저장' })).toBeDisabled()
  currentItems = []
  view.rerender(<SlackPanel workspaceId="two" />)
  await screen.findByText('이 Workspace의 수신 메시지가 없습니다.')
  expect(screen.queryByRole('button', { name: '새 대화로 저장' })).not.toBeInTheDocument()
  expect(api.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)
})

it('긴 원문은 조용히 자르지 않고 사용자가 질문을 입력해야 한다', async () => {
  currentItems = [{ ...item, event: { ...event, text: 'x'.repeat(300) } }]
  render(<SlackPanel workspaceId="one" />)
  await screen.findByText(/긴 메시지입니다/)
  expect(screen.getByLabelText('대화에 보낼 질문')).toHaveValue('')
  expect(screen.getByRole('button', { name: '대화에 질문 저장' })).toBeDisabled()
  await userEvent.type(screen.getByLabelText('대화에 보낼 질문'), '선택한 가상 질문')
  expect(screen.getByRole('button', { name: '대화에 질문 저장' })).toBeEnabled()
})

it('원문 HTML과 URL은 실행하거나 자동 로드하지 않는다', async () => {
  const text = '<img src="https://example.invalid/secret" onerror="alert(1)">'
  currentItems = [{ ...item, event: { ...event, text } }]
  const view = render(<SlackPanel workspaceId="one" />)
  expect(await screen.findByText(text, { selector: 'pre' })).toBeInTheDocument()
  expect(view.container.querySelector('img')).toBeNull()
  expect(view.container.querySelector('a')).toBeNull()
})

it('Workspace 전환 후 늦은 조회 응답과 대화 열기 콜백을 무시한다', async () => {
  currentItems = [item]
  const callback = vi.fn()
  const view = render(<SlackPanel workspaceId="one" onConversationSaved={callback} />)
  await screen.findByText(event.text, { selector: 'pre' })
  let finish!: (value: unknown) => void
  api.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  await userEvent.click(screen.getByRole('button', { name: '대화에 질문 저장' }))
  const signal = api.mock.calls.find(([path]) => path.endsWith('/conversation'))![1]!.signal!
  currentItems = []
  view.rerender(<SlackPanel workspaceId="two" onConversationSaved={callback} />)
  expect(signal.aborted).toBe(true)
  await act(async () => finish({ threadId: 'old-thread', bundleId: 'old-bundle' }))
  expect(callback).not.toHaveBeenCalled()
  expect(screen.queryByText(event.text, { selector: 'pre' })).not.toBeInTheDocument()
})

it('채널을 선택된 Workspace에만 명시적으로 연결한다', async () => {
  currentStatus = { ...initial, state: 'CONNECTED', teamId: 'TTEAM' }
  render(<SlackPanel workspaceId="one" />)
  await screen.findByText('Slack 수신 연결됨')
  await userEvent.type(screen.getByLabelText('Slack 채널 ID'), 'CCHANNEL')
  api.mockResolvedValueOnce({ teamId: 'TTEAM', channelId: 'CCHANNEL', workspaceId: 'one' })
  await userEvent.click(screen.getByRole('button', { name: '현재 Workspace로 수신' }))
  expect(api).toHaveBeenCalledWith('/slack/routes/CCHANNEL', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ workspaceId: 'one' }) }))
})

it('조회 실패는 연결 성공 표시와 새 저장을 잠그고 복구 후 다시 허용한다', async () => {
  currentStatus = { ...initial, state: 'CONNECTED', teamId: 'TTEAM' }
  currentItems = [item]
  render(<SlackPanel workspaceId="one" />)
  await screen.findByText('Slack 수신 연결됨')
  api.mockRejectedValueOnce(new Error('Synthetic read failure'))
  await userEvent.click(screen.getByRole('button', { name: 'Slack 수신함 새로고침' }))
  expect(await screen.findByText(/마지막 조회 결과/)).toBeInTheDocument()
  expect(screen.queryByText('Slack 수신 연결됨')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '대화에 질문 저장' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '현재 Workspace로 수신' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Slack 연결 해제' })).toBeEnabled()
  await userEvent.click(screen.getByRole('button', { name: 'Slack 수신함 새로고침' }))
  await screen.findByText('Slack 수신 연결됨')
  expect(screen.queryByText(/마지막 조회 결과/)).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '대화에 질문 저장' })).toBeEnabled()
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
})

it('재시작 후 저장 영수증을 열 때 bundle이 아닌 thread를 전달하며 재저장하지 않는다', async () => {
  const receipt = { threadId: 'restored-thread', bundleId: 'restored-bundle' }
  currentItems = [{ ...item, sent: receipt, sentQuestion: 'Saved original question',
    event: { ...event, text: 'Corrected text', revisionTimestamp: '1800000002.1' } }]
  const callback = vi.fn()
  render(<SlackPanel workspaceId="one" onConversationSaved={callback} />)
  await screen.findByText(/저장한 질문은 Slack 수정/)
  expect(screen.queryByRole('button', { name: '대화에 질문 저장' })).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '저장한 대화 열기' }))
  expect(callback).toHaveBeenCalledExactlyOnceWith('one', 'restored-thread', 'restored-bundle')
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
})

it('느린 조회는 중복 폴링하지 않고 시간 초과 뒤 다음 조회로 복구한다', async () => {
  vi.useFakeTimers()
  let first = true
  let recovered = false
  api.mockImplementation(async (path, options) => {
    if (path === '/slack/status' && first) {
      first = false
      return new Promise((_, reject) => options?.signal?.addEventListener('abort', () => reject(new Error('aborted')), { once: true }))
    }
    if (path === '/slack/status') {
      if (!recovered) throw new Error('Synthetic offline')
      return { ...initial, state: 'CONNECTED', teamId: 'TTEAM' }
    }
    return []
  })
  const view = render(<SlackPanel workspaceId="one" />)
  try {
    await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
    expect(api).toHaveBeenCalledTimes(3)
    await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
    expect(screen.getByText(/마지막 조회 결과/)).toBeInTheDocument()
    recovered = true
    await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
    expect(screen.getByText('Slack 수신 연결됨')).toBeInTheDocument()
    expect(screen.queryByText(/마지막 조회 결과/)).not.toBeInTheDocument()
    expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
  } finally { view.unmount(); vi.useRealTimers() }
})
