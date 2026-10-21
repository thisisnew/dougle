import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import AnswerHistoryPanel from './AnswerHistoryPanel'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const props = { workspaceId: 'one', onClose: vi.fn() }
const job = { id: 'job-one', workspaceId: 'one', bundleId: 'bundle', approvalId: 'approval', status: 'SUCCEEDED', provider: 'CODEX', requestedModel: 'fixture-model', answer: '<script>bad()</script> [R1/E1]', error: null, createdAt: '2026-10-22T00:00:00Z' }
const bundle = { id: 'bundle', workspaceId: 'one', digest: 'hash', payload: { question: '관측 질문', domains: ['가상'], includeKnowledge: true,
  items: [{ label: 'R1', title: '게시 기록', kind: 'KNOWLEDGE', version: 2, startLine: 1, endLine: 2, quote: '<img src="https://invalid.example/spy">',
    supports: [{ label: 'E1', name: '관측.txt', startLine: 3, endLine: 4, quote: '당시 원본' }] }] } }

beforeEach(() => {
  api.mockReset(); props.onClose.mockReset()
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
  api.mockImplementation(async path => {
    if (path.endsWith('/answer-jobs')) return [job]
    if (path.endsWith('/evidence-bundles')) return [{ id: 'bundle', question: '관측 질문' }]
    if (path.endsWith('/answer-jobs/job-one')) return job
    return bundle
  })
})

async function openJob() { await userEvent.click(await screen.findByRole('button', { name: /관측 질문 · 완료/ })) }

it('목록은 GET 두 번만 요청하고 선택한 답변의 당시 근거를 먼저 표시한다', async () => {
  render(<AnswerHistoryPanel {...props} />)
  await screen.findByText('1개 표시 · 전체 1개')
  expect(api).toHaveBeenCalledTimes(2)
  await openJob()
  expect(await screen.findByLabelText('이력 답변')).toHaveTextContent(job.answer)
  const detail = screen.getByLabelText('답변 이력 상세')
  expect(within(detail).getByText('[R1/E1] 관측.txt · 3–4행')).toBeInTheDocument()
  expect(detail.textContent!.indexOf('답변 당시 근거')).toBeLessThan(detail.textContent!.indexOf('저장된 답변'))
  expect(api.mock.calls.every(([, options]) => !options?.method || options.method === 'GET')).toBe(true)
  expect(document.querySelector('script, img')).toBeNull()
  expect(screen.queryByRole('button', { name: /전송|중단|삭제/ })).not.toBeInTheDocument()
})

it('질문 검색과 상태 필터는 서버 요청 없이 작동한다', async () => {
  api.mockImplementation(async path => path.endsWith('/answer-jobs') ? [job, { ...job, id: 'failed-job', bundleId: 'other', status: 'FAILED', answer: null }]
    : [{ id: 'bundle', question: '관측 질문' }, { id: 'other', question: '다른 질문' }])
  render(<AnswerHistoryPanel {...props} />)
  await screen.findByText('2개 표시 · 전체 2개')
  await userEvent.type(screen.getByLabelText('이력 질문 검색'), '관측')
  expect(screen.getByText('1개 표시 · 전체 2개')).toBeInTheDocument()
  await userEvent.selectOptions(screen.getByLabelText('이력 상태'), 'FAILED')
  expect(screen.getByText('0개 표시 · 전체 2개')).toBeInTheDocument()
  await userEvent.clear(screen.getByLabelText('이력 질문 검색'))
  expect(screen.getByRole('button', { name: /다른 질문 · 실패/ })).toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(2)
})

it('실패 작업의 오류는 표시하지만 완료되지 않은 답변은 숨긴다', async () => {
  api.mockImplementation(async path => path.endsWith('/answer-jobs') ? [job] : path.endsWith('/evidence-bundles') ? [{ id: 'bundle', question: '관측 질문' }]
    : path.includes('/answer-jobs/') ? { ...job, status: 'FAILED', answer: '저장하면 안 되는 임시 글', error: '가상 생성 실패' } : bundle)
  render(<AnswerHistoryPanel {...props} />); await openJob()
  expect(await screen.findByRole('alert')).toHaveTextContent('가상 생성 실패')
  expect(screen.queryByText('저장하면 안 되는 임시 글')).not.toBeInTheDocument()
  expect(screen.queryByLabelText('이력 답변')).not.toBeInTheDocument()
})

it('상세 조회 중 삭제되면 이전 답변을 지우고 오류를 표시한다', async () => {
  render(<AnswerHistoryPanel {...props} />); await openJob(); await screen.findByLabelText('이력 답변')
  api.mockRejectedValueOnce(new Error('자료를 찾을 수 없습니다.'))
  await userEvent.click(screen.getByRole('button', { name: /관측 질문 · 완료/ }))
  expect(await screen.findByRole('alert')).toHaveTextContent('자료를 찾을 수 없습니다.')
  expect(screen.queryByLabelText('이력 답변')).not.toBeInTheDocument()
})

it('다른 Workspace의 작업 상세는 근거 요청 전에 거절한다', async () => {
  render(<AnswerHistoryPanel {...props} />); await screen.findByText('1개 표시 · 전체 1개')
  api.mockResolvedValueOnce({ ...job, workspaceId: 'other' })
  await openJob()
  expect(await screen.findByRole('alert')).toHaveTextContent('범위가 다릅니다')
  expect(api.mock.calls.some(([path]) => path.endsWith('/evidence-bundles/bundle'))).toBe(false)
})

it('다른 묶음의 근거를 답변과 함께 표시하지 않는다', async () => {
  render(<AnswerHistoryPanel {...props} />); await screen.findByText('1개 표시 · 전체 1개')
  api.mockResolvedValueOnce(job).mockResolvedValueOnce({ ...bundle, id: 'different' })
  await openJob()
  expect(await screen.findByRole('alert')).toHaveTextContent('근거 묶음의 범위가 다릅니다')
  expect(screen.queryByLabelText('답변 이력 상세')).not.toBeInTheDocument()
})

it('Workspace 전환 시 이전 요청을 중단하고 늦은 응답을 무시한다', async () => {
  let resolveOld!: (value: unknown) => void
  api.mockImplementation(async path => path === '/workspaces/one/answer-jobs' ? new Promise(resolve => { resolveOld = resolve }) : [])
  const view = render(<AnswerHistoryPanel {...props} />)
  await waitFor(() => expect(api).toHaveBeenCalledTimes(2))
  const signal = api.mock.calls[0][1]!.signal!
  view.rerender(<AnswerHistoryPanel {...props} workspaceId="two" />)
  expect(signal.aborted).toBe(true)
  await screen.findByText('저장된 답변 작업이 없습니다.')
  await act(async () => resolveOld([job]))
  expect(screen.queryByRole('button', { name: /관측 질문/ })).not.toBeInTheDocument()
})

it('닫기와 unmount는 관측 요청만 중단하고 작업 취소 API를 호출하지 않는다', async () => {
  api.mockImplementation(() => new Promise(() => {}))
  const view = render(<AnswerHistoryPanel {...props} />)
  const signal = api.mock.calls[0][1]!.signal!
  await userEvent.click(screen.getByRole('button', { name: '닫기' }))
  expect(props.onClose).toHaveBeenCalledOnce()
  view.unmount(); expect(signal.aborted).toBe(true)
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
})

it('새로고침하면 삭제된 작업과 이전 상세가 사라진다', async () => {
  render(<AnswerHistoryPanel {...props} />); await openJob(); await screen.findByLabelText('이력 답변')
  api.mockResolvedValue([])
  await userEvent.click(screen.getByRole('button', { name: '이력 새로고침' }))
  expect(await screen.findByText('저장된 답변 작업이 없습니다.')).toBeInTheDocument()
  expect(screen.queryByLabelText('답변 이력 상세')).not.toBeInTheDocument()
})
