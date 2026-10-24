import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import AnswerJobsPanel from './AnswerJobsPanel'
import { request } from './api'
import { FakeEventSource } from './test/FakeEventSource'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const props = { workspaceId: 'workspace', bundleId: 'bundle', approval: { id: 'approval', digest: 'digest' } }
const done = { id: 'job', bundleId: 'bundle', approvalId: 'approval', status: 'SUCCEEDED', provider: 'CODEX', requestedModel: 'model', answer: '<script>bad()</script> [R1]', error: null }
beforeEach(() => { api.mockReset(); FakeEventSource.instances = []; vi.stubGlobal('EventSource', FakeEventSource) })
afterEach(() => vi.unstubAllGlobals())

it('간편 질문은 접수된 작업을 바로 관측하고 중단하며 다시 전송하지 않는다', async () => {
  api.mockResolvedValue({ ...done, status: 'CANCELLED', answer: null })
  render(<AnswerJobsPanel {...props} approval={null} compact initialJob={{ ...done, status: 'RUNNING', answer: null }} />)
  expect(api).not.toHaveBeenCalled()
  const source = FakeEventSource.instances[0]
  expect(source).toBeDefined()
  expect(screen.queryByRole('button', { name: '답변 작업 조회·새로고침' })).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '답변 중단' }))
  expect(await screen.findByText('중단됨')).toBeInTheDocument()
  expect(source.closed).toBe(true)
  expect(api.mock.calls[0][0]).toMatch(/answer-jobs\/job\/cancel$/)
})

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
  const source = FakeEventSource.instances[0]
  await userEvent.click(await screen.findByRole('button', { name: '답변 중단' }))
  expect(await screen.findByText('중단됨')).toBeInTheDocument()
  expect(source.closed).toBe(true)
  act(() => { source.emit('evidence', { id: 'bundle', workspaceId: 'workspace', digest: 'digest' }); source.emit('job', { ...done, workspaceId: 'workspace' }) })
  expect(api.mock.calls[1][0]).toBe('/workspaces/workspace/answer-jobs/job/cancel')
  expect(screen.queryByLabelText('AI 답변')).not.toBeInTheDocument()
})

it('SSE 근거 확인 후 완료 답변을 안전하게 표시하고 폴링하지 않는다', async () => {
  api.mockResolvedValueOnce([{ ...done, status: 'RUNNING', answer: null }])
  render(<AnswerJobsPanel {...props} approval={null} />)
  await userEvent.click(screen.getByRole('button', { name: '답변 작업 조회·새로고침' }))
  const source = FakeEventSource.instances[0]
  act(() => source.emit('evidence', { id: 'bundle', workspaceId: 'workspace', digest: 'digest' }))
  expect(screen.getByLabelText('SSE 연결 상태')).toHaveTextContent('근거 확인 완료')
  expect(screen.queryByLabelText('AI 답변')).not.toBeInTheDocument()
  act(() => source.emit('job', { ...done, workspaceId: 'workspace' }))
  expect(await screen.findByLabelText('AI 답변')).toHaveTextContent(done.answer)
  expect(document.querySelector('script')).toBeNull()
  expect(source.closed).toBe(true)
  expect(api).toHaveBeenCalledOnce()
})

it('Workspace 변경 시 관측을 닫고 늦은 응답과 이전 화면을 버린다', async () => {
  api.mockResolvedValueOnce([{ ...done, status: 'RUNNING', answer: null }])
  const view = render(<AnswerJobsPanel {...props} approval={null} />)
  await userEvent.click(screen.getByRole('button', { name: '답변 작업 조회·새로고침' }))
  const source = FakeEventSource.instances[0]
  view.rerender(<AnswerJobsPanel {...props} workspaceId="other" approval={null} />)
  expect(source.closed).toBe(true)
  act(() => { source.emit('evidence', { id: 'bundle', workspaceId: 'workspace', digest: 'digest' }); source.emit('job', { ...done, workspaceId: 'workspace' }) })
  expect(screen.queryByLabelText('AI 답변')).not.toBeInTheDocument()
  expect(screen.queryByLabelText('저장된 답변 작업')).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledOnce()
})

it('SSE 실패 후 새로고침으로 같은 작업을 다시 관측한다', async () => {
  api.mockResolvedValue([{ ...done, status: 'RUNNING', answer: null }])
  const view = render(<AnswerJobsPanel {...props} approval={null} />)
  await userEvent.click(screen.getByRole('button', { name: '답변 작업 조회·새로고침' }))
  act(() => FakeEventSource.instances[0].emit('unavailable', {}))
  await screen.findByRole('alert')
  await userEvent.click(screen.getByRole('button', { name: '답변 작업 조회·새로고침' }))
  expect(FakeEventSource.instances).toHaveLength(2)
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  view.unmount()
  expect(FakeEventSource.instances[1].closed).toBe(true)
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
})

it('미검증 출력은 텍스트로만 표시하고 검증 실패하면 지운다', async () => {
  api.mockResolvedValueOnce([{ ...done, status: 'RUNNING', answer: null }])
  render(<AnswerJobsPanel {...props} approval={null} />)
  await userEvent.click(screen.getByRole('button', { name: '답변 작업 조회·새로고침' }))
  const source = FakeEventSource.instances[0]
  act(() => {
    source.emit('evidence', { id: 'bundle', workspaceId: 'workspace', digest: 'digest' })
    source.emit('job', { ...done, workspaceId: 'workspace', status: 'RUNNING', answer: null })
    source.emit('partial', { workspaceId: 'workspace', bundleId: 'bundle', jobId: 'job', text: '<img src="https://invalid.example" />', unverified: true })
  })
  expect(screen.getByLabelText('미검증 임시 출력')).toHaveTextContent('인용·사실성 미검증')
  expect(document.querySelector('img')).toBeNull()
  expect(screen.queryByLabelText('AI 답변')).not.toBeInTheDocument()
  act(() => source.emit('job', { ...done, workspaceId: 'workspace', status: 'FAILED', answer: null }))
  expect(screen.queryByLabelText('미검증 임시 출력')).not.toBeInTheDocument()
})

it('닫으면 조회 대기를 취소하되 서버 중단을 가장하지 않는다', async () => {
  api.mockImplementation(() => new Promise(() => {}))
  const view = render(<AnswerJobsPanel {...props} approval={null} />)
  await userEvent.click(screen.getByRole('button', { name: '답변 작업 조회·새로고침' }))
  await waitFor(() => expect(api).toHaveBeenCalledOnce())
  const signal = api.mock.calls[0][1]?.signal
  view.unmount(); expect(signal?.aborted).toBe(true)
})
