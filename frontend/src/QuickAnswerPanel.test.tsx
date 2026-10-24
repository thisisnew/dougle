import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import QuickAnswerPanel from './QuickAnswerPanel'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const props = { workspaceId: 'one', bundleId: 'bundle', bundleDigest: 'frozen' }
const preview = { bundleId: 'bundle', bundleDigest: 'frozen', provider: 'CODEX', requestedModel: 'model', digest: 'digest',
  prompt: '<script>bad()</script> [R1] synthetic', validation: { eligible: true } }
const approval = { id: 'approval', bundleId: 'bundle', digest: 'digest', provider: 'CODEX', requestedModel: 'model' }
const job = { id: 'job', bundleId: 'bundle', approvalId: 'approval', status: 'SUCCEEDED', provider: 'CODEX', requestedModel: 'model', answer: 'Synthetic answer [R1]', error: null }
beforeEach(() => { api.mockReset(); localStorage.clear() })

async function prepare() {
  await userEvent.click(screen.getByRole('button', { name: '이 자료로 답변 준비' }))
  await screen.findByRole('button', { name: '동의하고 답변 받기' })
}

it('명시적 준비와 동의 전에는 외부 호출 없이 전문을 확인할 수 있다', async () => {
  api.mockResolvedValue(preview)
  render(<QuickAnswerPanel {...props} />)
  expect(api).not.toHaveBeenCalled()
  await prepare()
  expect(api).toHaveBeenCalledOnce()
  expect(api.mock.calls[0][0]).toMatch(/answer-preview$/)
  expect(screen.getByRole('button', { name: '동의하고 답변 받기' })).toBeDisabled()
  expect(document.querySelector('script')).toBeNull()
  await userEvent.click(screen.getByText('보낼 내용 확인'))
  expect(screen.getByText(preview.prompt)).toBeVisible()
})

it('한 번의 동의로 CAS 승인 후 같은 조건의 답변을 접수하고 결과를 표시한다', async () => {
  api.mockResolvedValueOnce(preview).mockResolvedValueOnce({ status: 'NONE', approval: null })
    .mockResolvedValueOnce({ status: 'APPROVED', approval }).mockResolvedValueOnce(job)
  render(<QuickAnswerPanel {...props} />)
  await prepare()
  await userEvent.click(screen.getByRole('checkbox'))
  await userEvent.click(screen.getByRole('button', { name: '동의하고 답변 받기' }))
  expect(await screen.findByLabelText('AI 답변')).toHaveTextContent(job.answer)
  expect(JSON.parse(api.mock.calls[2][1]!.body as string)).toEqual({ digest: 'digest', provider: 'CODEX', requestedModel: 'model', acknowledged: true, expectedApprovalId: null })
  expect(JSON.parse(api.mock.calls[3][1]!.body as string)).toEqual({ requestId: expect.any(String), approvalId: 'approval', digest: 'digest', approved: true })
  expect(screen.queryByRole('button', { name: '동의하고 답변 받기' })).not.toBeInTheDocument()
})

it('전송 응답 유실 후 재승인·새 ID 없이 같은 요청으로 접수를 확인한다', async () => {
  api.mockResolvedValueOnce(preview).mockResolvedValueOnce({ status: 'NONE', approval: null })
    .mockResolvedValueOnce({ status: 'APPROVED', approval }).mockRejectedValueOnce(new Error('응답 유실')).mockResolvedValueOnce(job)
  render(<QuickAnswerPanel {...props} />)
  await prepare()
  await userEvent.click(screen.getByRole('checkbox'))
  await userEvent.click(screen.getByRole('button', { name: '동의하고 답변 받기' }))
  await screen.findByRole('alert')
  expect(screen.getByLabelText('사용할 AI')).toBeDisabled()
  await userEvent.click(screen.getByRole('button', { name: '접수 상태 다시 확인' }))
  await screen.findByLabelText('AI 답변')
  expect(api.mock.calls[3][1]!.body).toBe(api.mock.calls[4][1]!.body)
  expect(api).toHaveBeenCalledTimes(5)
})

it('변경된 근거나 다른 묶음 미리보기로는 동의 버튼을 열지 않는다', async () => {
  api.mockResolvedValue({ ...preview, validation: { eligible: false } })
  render(<QuickAnswerPanel {...props} />)
  await userEvent.click(screen.getByRole('button', { name: '이 자료로 답변 준비' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('참고 자료가 변경')
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  api.mockResolvedValue({ ...preview, bundleId: 'other' })
  await userEvent.click(screen.getByRole('button', { name: '이 자료로 답변 준비' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('참고 자료가 변경')
})

it('명시적으로 선택한 모델과 다른 미리보기는 전송하지 않는다', async () => {
  api.mockResolvedValue(preview)
  render(<QuickAnswerPanel {...props} connectedProvider="CODEX" connectedModel="different-model" requireConnection />)
  await userEvent.click(screen.getByRole('button', { name: '이 자료로 답변 준비' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('참고 자료가 변경')
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledOnce()
})

it('제공자 변경은 준비한 요청과 동의를 폐기한다', async () => {
  api.mockResolvedValue(preview)
  render(<QuickAnswerPanel {...props} />)
  await prepare()
  await userEvent.click(screen.getByRole('checkbox'))
  await userEvent.selectOptions(screen.getByLabelText('사용할 AI'), 'CLAUDE')
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '동의하고 답변 받기' })).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledOnce()
})

it('잘못된 승인 조건이나 승인 충돌이면 작업을 접수하지 않는다', async () => {
  api.mockResolvedValueOnce(preview).mockResolvedValueOnce({ status: 'EXPIRED', approval })
    .mockResolvedValueOnce({ status: 'APPROVED', approval: { ...approval, provider: 'CLAUDE' } })
  render(<QuickAnswerPanel {...props} />)
  await prepare()
  await userEvent.click(screen.getByRole('checkbox'))
  await userEvent.click(screen.getByRole('button', { name: '동의하고 답변 받기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('승인 조건')
  expect(api.mock.calls.some(([path]) => path.endsWith('/answer-jobs'))).toBe(false)
  expect(JSON.parse(api.mock.calls[2][1]!.body as string).expectedApprovalId).toBe('approval')
  expect(screen.getByRole('checkbox')).not.toBeChecked()
})

it('승인 조회 중 닫으면 후속 승인·전송을 하지 않는다', async () => {
  let resolveState!: (value: unknown) => void
  api.mockResolvedValueOnce(preview).mockImplementationOnce(() => new Promise(resolve => { resolveState = resolve }))
  const view = render(<QuickAnswerPanel {...props} />)
  await prepare()
  await userEvent.click(screen.getByRole('checkbox'))
  await userEvent.click(screen.getByRole('button', { name: '동의하고 답변 받기' }))
  expect(screen.getByLabelText('사용할 AI')).toBeDisabled()
  view.unmount()
  expect(api.mock.calls[1][1]?.signal?.aborted).toBe(true)
  resolveState({ status: 'NONE', approval: null })
  await waitFor(() => expect(api).toHaveBeenCalledTimes(2))
})
