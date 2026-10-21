import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import ProposalAcceptance from './ProposalAcceptance'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const props = { workspaceId: 'one', jobId: 'job', sourceVersion: 3, resultVersion: 1, eligible: true, disabled: false, onApplied: vi.fn() }
const open = { status: 'OPEN', decisionRevision: 2, acceptance: null }
const accepted = { ...open, status: 'ACCEPTED', acceptance: { appliedVersion: 4, note: 'synthetic adoption', acceptedAt: 'now' } }
beforeEach(() => { api.mockReset(); props.onApplied.mockReset() })

it('내용 확인과 이유가 있어야 버전·처리 revision에 묶어 채택한다', async () => {
  const user = userEvent.setup()
  api.mockResolvedValueOnce(open).mockResolvedValueOnce(accepted)
  render(<ProposalAcceptance {...props} />)
  const submit = await screen.findByRole('button', { name: '전체 결과를 새 초안으로 채택' })
  expect(submit).toBeDisabled()
  await user.type(screen.getByLabelText('채택 이유'), 'synthetic adoption')
  expect(submit).toBeDisabled()
  await user.click(screen.getByRole('checkbox'))
  await user.click(submit)
  expect(await screen.findByText(/채택 완료 · 원래 문서 v4/)).toBeInTheDocument()
  expect(JSON.parse(api.mock.calls[1][1]!.body as string)).toEqual({
    expectedRevision: 2, expectedSourceVersion: 3, expectedResultVersion: 1, confirmed: true, note: 'synthetic adoption',
  })
  expect(props.onApplied).toHaveBeenCalledOnce()
})

it('충돌 시 이유는 보존하고 확인을 해제하며 자동 재시도하지 않는다', async () => {
  api.mockResolvedValueOnce(open).mockRejectedValueOnce(new Error('다른 편집이 저장되었습니다'))
  render(<ProposalAcceptance {...props} />)
  await screen.findByLabelText('채택 이유')
  await userEvent.type(screen.getByLabelText('채택 이유'), 'keep note')
  await userEvent.click(screen.getByRole('checkbox'))
  await userEvent.click(screen.getByRole('button', { name: '전체 결과를 새 초안으로 채택' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('비교 새로고침')
  expect(screen.getByLabelText('채택 이유')).toHaveValue('keep note')
  expect(screen.getByRole('checkbox')).not.toBeChecked()
  expect(api).toHaveBeenCalledTimes(2)
  expect(props.onApplied).not.toHaveBeenCalled()
})

it('거절 상태·편집 중·충돌 제안은 채택할 수 없다', async () => {
  api.mockResolvedValue({ ...open, status: 'REJECTED' })
  const view = render(<ProposalAcceptance {...props} />)
  await screen.findByLabelText('채택 이유')
  await userEvent.type(screen.getByLabelText('채택 이유'), 'reason')
  await userEvent.click(screen.getByRole('checkbox'))
  expect(screen.getByRole('button', { name: '전체 결과를 새 초안으로 채택' })).toBeDisabled()
  view.rerender(<ProposalAcceptance {...props} disabled eligible={false} />)
  expect(screen.getByLabelText('채택 이유')).toBeDisabled()
  expect(screen.getByText(/전체 채택은 차단/)).toBeInTheDocument()
})

it('실패 후 기록을 조회해 이미 저장된 채택을 확인할 수 있다', async () => {
  api.mockRejectedValueOnce(new Error('연결 실패')).mockResolvedValueOnce(accepted)
  render(<ProposalAcceptance {...props} />)
  await screen.findByRole('alert')
  await userEvent.click(screen.getByRole('button', { name: '채택 기록 새로고침' }))
  expect(await screen.findByText(/채택 완료/)).toBeInTheDocument()
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
})

it('저장 중 중복 클릭을 막고 화면 종료 시 요청을 취소한다', async () => {
  api.mockResolvedValueOnce(open).mockReturnValueOnce(new Promise(() => {}))
  const view = render(<ProposalAcceptance {...props} />)
  await screen.findByLabelText('채택 이유')
  await userEvent.type(screen.getByLabelText('채택 이유'), 'reason')
  await userEvent.click(screen.getByRole('checkbox'))
  await userEvent.click(screen.getByRole('button', { name: '전체 결과를 새 초안으로 채택' }))
  expect(screen.getByRole('button', { name: '채택 저장 중…' })).toBeDisabled()
  await waitFor(() => expect(api).toHaveBeenCalledTimes(2))
  const signal = api.mock.calls[1][1]!.signal as AbortSignal
  view.unmount()
  expect(signal.aborted).toBe(true)
})
