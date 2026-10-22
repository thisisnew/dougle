import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { request } from './api'
import ConversationShelf from './ConversationShelf'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const room = { id: 'room', workspaceId: 'one', title: '관측 보관함', revision: 0 }
const props = { workspaceId: 'one', selectedJobId: 'job', onSelectJob: vi.fn() }
beforeEach(() => {
  vi.clearAllMocks()
  api.mockImplementation(async path => path.endsWith('/room') ? { conversation: room, turns: [] } : [room])
})

it('처음에는 목록만 읽고 명시적 선택 후 보관함을 연다', async () => {
  render(<ConversationShelf {...props} />)
  await userEvent.click(await screen.findByRole('button', { name: room.title }))
  expect(await screen.findByLabelText('선택한 보관함')).toHaveTextContent('연결된 답변이 없습니다.')
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
})

it('이름을 입력해 명시적으로 생성하며 전송 승인은 만들지 않는다', async () => {
  render(<ConversationShelf {...props} />)
  await screen.findByRole('button', { name: room.title })
  await userEvent.type(screen.getByLabelText('새 보관함 이름'), '새 보관함')
  api.mockResolvedValueOnce({ ...room, id: 'new', title: '새 보관함' }).mockResolvedValueOnce([room])
  await userEvent.click(screen.getByRole('button', { name: '보관함 만들기' }))
  expect(await screen.findByLabelText('선택한 보관함')).toHaveTextContent('새 보관함')
  expect(api).toHaveBeenCalledWith('/workspaces/one/conversations', expect.objectContaining({ method: 'POST', body: JSON.stringify({ title: '새 보관함' }) }))
  expect(api.mock.calls.some(([path]) => /approval|answer-jobs/.test(path))).toBe(false)
})

it('완료 답변을 조회 revision과 함께 추가하고 순서대로 열기 콜백을 호출한다', async () => {
  render(<ConversationShelf {...props} />)
  await userEvent.click(await screen.findByRole('button', { name: room.title }))
  await screen.findByLabelText('선택한 보관함')
  api.mockResolvedValueOnce({ conversation: { ...room, revision: 1 }, turns: [{ jobId: 'job', position: 1 }] }).mockResolvedValueOnce([room])
  await userEvent.click(screen.getByRole('button', { name: '현재 완료 답변 추가' }))
  await userEvent.click(await screen.findByRole('button', { name: '1번 답변 · job' }))
  expect(props.onSelectJob).toHaveBeenCalledWith('job')
  expect(api).toHaveBeenCalledWith('/workspaces/one/conversations/room/turns', expect.objectContaining({ method: 'POST', body: JSON.stringify({ jobId: 'job', expectedRevision: 0 }) }))
})

it('삭제는 재확인을 요구하며 보관함 경로만 삭제한다', async () => {
  render(<ConversationShelf {...props} selectedJobId={null} />)
  await userEvent.click(await screen.findByRole('button', { name: room.title }))
  await screen.findByLabelText('선택한 보관함')
  expect(screen.getByRole('button', { name: '현재 완료 답변 추가' })).toBeDisabled()
  await userEvent.click(screen.getByRole('button', { name: '보관함 삭제' }))
  expect(api.mock.calls.some(([, options]) => options?.method === 'DELETE')).toBe(false)
  api.mockResolvedValueOnce(undefined).mockResolvedValueOnce([])
  await userEvent.click(screen.getByRole('button', { name: '보관함만 삭제 확인' }))
  await waitFor(() => expect(screen.queryByLabelText('선택한 보관함')).not.toBeInTheDocument())
  expect(api).toHaveBeenCalledWith('/workspaces/one/conversations/room', expect.objectContaining({ method: 'DELETE' }))
})

it('범위가 다른 상세나 충돌 오류는 이전 상세를 숨긴다', async () => {
  render(<ConversationShelf {...props} />)
  await screen.findByRole('button', { name: room.title })
  api.mockResolvedValueOnce({ conversation: { ...room, workspaceId: 'other' }, turns: [] })
  await userEvent.click(screen.getByRole('button', { name: room.title }))
  expect(await screen.findByRole('alert')).toHaveTextContent('범위가 다릅니다')
  expect(screen.queryByLabelText('선택한 보관함')).not.toBeInTheDocument()
})

it('닫으면 조회 요청을 중단하고 늦은 결과를 사용하지 않는다', async () => {
  let resolve!: (value: unknown) => void
  api.mockImplementationOnce(() => new Promise(complete => { resolve = complete }))
  const view = render(<ConversationShelf {...props} />)
  await waitFor(() => expect(api).toHaveBeenCalledTimes(1))
  const signal = api.mock.calls[0][1]!.signal!
  view.unmount()
  expect(signal.aborted).toBe(true)
  resolve([room])
  expect(props.onSelectJob).not.toHaveBeenCalled()
})
