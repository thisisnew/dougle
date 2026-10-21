import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import ProposalDecisions from './ProposalDecisions'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const props = { workspaceId: 'one', jobId: 'job', sourceVersion: 2, resultVersion: 3, disabled: false }
const rejected = { revision: 1, status: 'REJECTED', sourceVersion: 2, resultVersion: 3, note: '가상 이유', createdAt: 'now' }
beforeEach(() => { api.mockReset() })

it('명시적 이유와 비교 버전에 묶어 거절을 저장하고 재검토 이력을 추가한다', async () => {
  const user = userEvent.setup()
  api.mockResolvedValueOnce([]).mockResolvedValueOnce({ status: 'OPEN' }).mockResolvedValueOnce([rejected])
    .mockResolvedValueOnce([{ ...rejected, revision: 2, status: 'OPEN', note: '다시 확인' }, rejected])
  render(<ProposalDecisions {...props} />)
  expect(await screen.findByRole('button', { name: '제안 거절 기록' })).toBeDisabled()
  await user.type(screen.getByLabelText('처리 이유'), '가상 이유')
  await user.click(screen.getByRole('button', { name: '제안 거절 기록' }))
  expect(await screen.findByText('처리 상태: 거절됨')).toBeInTheDocument()
  expect(JSON.parse(api.mock.calls[2][1]!.body as string)).toEqual({
    expectedRevision: 0, expectedSourceVersion: 2, expectedResultVersion: 3, status: 'REJECTED', note: '가상 이유',
  })
  await user.type(screen.getByLabelText('처리 이유'), '다시 확인')
  await user.click(screen.getByRole('button', { name: '재검토로 돌리기' }))
  expect(await screen.findByText('#2 재검토')).toBeInTheDocument()
  expect(screen.getByText('#1 거절')).toBeInTheDocument()
  expect(JSON.parse(api.mock.calls[3][1]!.body as string).expectedRevision).toBe(1)
})

it('충돌 시 입력을 보존하고 자동 재시도하지 않는다', async () => {
  const user = userEvent.setup()
  api.mockResolvedValueOnce([]).mockResolvedValueOnce({ status: 'OPEN' }).mockRejectedValueOnce(new Error('문서가 변경되었습니다'))
  render(<ProposalDecisions {...props} />)
  await screen.findByText('처리 상태: 검토 대기')
  await user.type(screen.getByLabelText('처리 이유'), '보존할 이유')
  await user.click(screen.getByRole('button', { name: '제안 거절 기록' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('비교 새로고침')
  expect(screen.getByLabelText('처리 이유')).toHaveValue('보존할 이유')
  expect(api).toHaveBeenCalledTimes(3)
})

it('조회 실패를 복구하며 편집 중에는 처리 변경을 차단한다', async () => {
  api.mockRejectedValueOnce(new Error('조회 실패')).mockResolvedValueOnce({ status: 'OPEN' })
    .mockResolvedValueOnce([rejected]).mockResolvedValueOnce({ status: 'REJECTED' })
  const view = render(<ProposalDecisions {...props} />)
  await screen.findByRole('alert')
  await userEvent.click(screen.getByRole('button', { name: '처리 기록 새로고침' }))
  await screen.findByText('처리 상태: 거절됨')
  view.rerender(<ProposalDecisions {...props} disabled />)
  expect(screen.getByLabelText('처리 이유')).toBeDisabled()
  expect(screen.getByRole('button', { name: '재검토로 돌리기' })).toBeDisabled()
})

it('저장 중 중복 클릭을 막고 닫을 때 요청을 취소한다', async () => {
  api.mockResolvedValueOnce([]).mockResolvedValueOnce({ status: 'OPEN' }).mockReturnValueOnce(new Promise(() => {}))
  const view = render(<ProposalDecisions {...props} />)
  await screen.findByText('처리 상태: 검토 대기')
  await userEvent.type(screen.getByLabelText('처리 이유'), 'reason')
  await userEvent.click(screen.getByRole('button', { name: '제안 거절 기록' }))
  expect(screen.getByRole('button', { name: '기록 중…' })).toBeDisabled()
  await waitFor(() => expect(api).toHaveBeenCalledTimes(3))
  const signal = api.mock.calls[2][1]!.signal as AbortSignal
  view.unmount()
  expect(signal.aborted).toBe(true)
})

it('채택한 제안은 검토 대기로 표시하거나 거절하지 않는다', async () => {
  api.mockResolvedValueOnce([]).mockResolvedValueOnce({ status: 'ACCEPTED' })
  render(<ProposalDecisions {...props} />)
  expect(await screen.findByText('처리 상태: 채택됨')).toBeInTheDocument()
  expect(screen.getByLabelText('처리 이유')).toBeDisabled()
  expect(screen.getByRole('button', { name: '제안 거절 기록' })).toBeDisabled()
})
