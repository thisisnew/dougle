import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import AnswerJobsPanel from './AnswerJobsPanel'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const props = { workspaceId: 'workspace', bundleId: 'bundle', approval: { id: 'approval', digest: 'digest' } }
const done = { id: 'job', bundleId: 'bundle', approvalId: 'approval', status: 'SUCCEEDED', provider: 'CODEX', requestedModel: 'model', answer: '<script>bad()</script> [R1]', error: null }
beforeEach(() => { api.mockReset() })

it('명시적 전송 확인 없이는 호출하지 않고 답변을 안전하게 표시한다', async () => {
  api.mockResolvedValue(done)
  render(<AnswerJobsPanel {...props} />)
  expect(api).not.toHaveBeenCalled()
  const button = screen.getByRole('button', { name: '승인한 요청 전송·답변 시작' })
  expect(button).toBeDisabled()
  await userEvent.click(screen.getByRole('checkbox'))
  await userEvent.click(button)
  expect(await screen.findByLabelText('AI 답변')).toHaveTextContent(done.answer)
  expect(document.querySelector('script')).toBeNull()
  expect(JSON.parse(api.mock.calls[0][1]!.body as string)).toEqual(expect.objectContaining({ approvalId: 'approval', digest: 'digest', approved: true }))
})

it('전송 응답 유실 후에도 같은 ID로 재시도한다', async () => {
  api.mockRejectedValueOnce(new Error('연결 끊김')).mockResolvedValueOnce(done)
  render(<AnswerJobsPanel {...props} />)
  const button = screen.getByRole('button', { name: '승인한 요청 전송·답변 시작' })
  await userEvent.click(screen.getByRole('checkbox')); await userEvent.click(button)
  await screen.findByRole('alert')
  await userEvent.click(screen.getByRole('checkbox')); await userEvent.click(button)
  await screen.findByLabelText('AI 답변')
  expect(api.mock.calls[0][1]!.body).toBe(api.mock.calls[1][1]!.body)
})

it('이전 작업을 조회할 수 있고 다른 묶음은 표시하지 않는다', async () => {
  api.mockResolvedValue([done, { ...done, id: 'other', bundleId: 'other-bundle' }])
  render(<AnswerJobsPanel {...props} approval={null} />)
  await userEvent.click(screen.getByRole('button', { name: '답변 작업 조회·새로고침' }))
  await screen.findByLabelText('AI 답변')
  expect(screen.getAllByRole('option')).toHaveLength(1)
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
})

it('실행 중 작업 중단 요청 후 늦은 답변을 표시하지 않는다', async () => {
  api.mockResolvedValueOnce([{ ...done, status: 'RUNNING', answer: null }]).mockResolvedValueOnce({ ...done, status: 'CANCELLED', answer: null })
  render(<AnswerJobsPanel {...props} approval={null} />)
  await userEvent.click(screen.getByRole('button', { name: '답변 작업 조회·새로고침' }))
  await userEvent.click(await screen.findByRole('button', { name: '답변 중단' }))
  expect(await screen.findByText('중단됨')).toBeInTheDocument()
  expect(api.mock.calls[1][0]).toBe('/workspaces/workspace/answer-jobs/job/cancel')
  expect(screen.queryByLabelText('AI 답변')).not.toBeInTheDocument()
})

it('상태를 폴링해 완료 답변을 표시하며 더 이상 조회하지 않는다', async () => {
  api.mockResolvedValueOnce([{ ...done, status: 'RUNNING', answer: null }]).mockResolvedValueOnce(done)
  render(<AnswerJobsPanel {...props} approval={null} />)
  await userEvent.click(screen.getByRole('button', { name: '답변 작업 조회·새로고침' }))
  expect(await screen.findByLabelText('AI 답변', {}, { timeout: 2500 })).toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(2)
})

it('닫으면 조회 대기를 취소하되 서버 중단을 가장하지 않는다', async () => {
  api.mockImplementation(() => new Promise(() => {}))
  const view = render(<AnswerJobsPanel {...props} approval={null} />)
  await userEvent.click(screen.getByRole('button', { name: '답변 작업 조회·새로고침' }))
  await waitFor(() => expect(api).toHaveBeenCalledOnce())
  const signal = api.mock.calls[0][1]?.signal
  view.unmount(); expect(signal?.aborted).toBe(true)
})
