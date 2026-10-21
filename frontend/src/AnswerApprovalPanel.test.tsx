import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import AnswerApprovalPanel from './AnswerApprovalPanel'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const preview = { digest: 'digest', provider: 'CODEX', requestedModel: 'model-one' }
const props = { workspaceId: 'workspace', bundleId: 'bundle', preview }
const approval = { ...preview, id: 'approval', bundleId: 'bundle', approvedAt: '2026-10-21T00:00:00Z', expiresAt: '2026-10-21T00:10:00Z', revokedAt: null }
beforeEach(() => { api.mockReset() })

it('상태 확인과 직접 동의 전에는 승인하지 않고 요청 조건을 묶어 저장한다', async () => {
  const user = userEvent.setup()
  api.mockResolvedValueOnce({ status: 'NONE', approval: null }).mockResolvedValueOnce({ status: 'APPROVED', approval })
  render(<AnswerApprovalPanel {...props} />)
  expect(api).not.toHaveBeenCalled()
  const submit = screen.getByRole('button', { name: '이 요청 승인 저장 (전송 안 함)' })
  expect(submit).toBeDisabled()
  await user.click(screen.getByRole('button', { name: '승인 상태 확인' }))
  expect(await screen.findByText('승인 없음')).toBeInTheDocument()
  expect(submit).toBeDisabled()
  await user.click(screen.getByRole('checkbox'))
  await user.click(submit)
  expect(await screen.findByText('확인 시점에 승인 유효')).toBeInTheDocument()
  expect(JSON.parse(api.mock.calls[1][1]!.body as string)).toEqual({ ...preview, acknowledged: true, expectedApprovalId: null })
  expect(screen.getByRole('checkbox')).not.toBeChecked()
})

it('미리보기 없이도 저장된 승인을 확인하고 해당 ID만 철회한다', async () => {
  api.mockResolvedValueOnce({ status: 'APPROVED', approval }).mockResolvedValueOnce({ status: 'REVOKED', approval: { ...approval, revokedAt: 'now' } })
  render(<AnswerApprovalPanel {...props} preview={null} />)
  await userEvent.click(screen.getByRole('button', { name: '승인 상태 확인' }))
  await userEvent.click(await screen.findByRole('button', { name: '저장된 승인 철회' }))
  expect(await screen.findByText('승인 철회됨')).toBeInTheDocument()
  expect(api.mock.calls[1][0]).toBe('/workspaces/workspace/evidence-bundles/bundle/answer-approval/approval')
  expect(api.mock.calls[1][1]?.method).toBe('DELETE')
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
})

it('만료·다른 조건을 표시하고 기존 승인 ID로 명시적으로 교체한다', async () => {
  api.mockResolvedValueOnce({ status: 'EXPIRED', approval: { ...approval, digest: 'old' } }).mockResolvedValueOnce({ status: 'APPROVED', approval })
  render(<AnswerApprovalPanel {...props} />)
  await userEvent.click(screen.getByRole('button', { name: '승인 상태 확인' }))
  expect(await screen.findByText('승인 만료됨')).toBeInTheDocument()
  expect(screen.getByText(/현재 미리보기와 다른 요청/)).toBeInTheDocument()
  await userEvent.click(screen.getByRole('checkbox'))
  await userEvent.click(screen.getByRole('button', { name: '이 요청 승인 저장 (전송 안 함)' }))
  await screen.findByText('확인 시점에 승인 유효')
  expect(JSON.parse(api.mock.calls[1][1]!.body as string).expectedApprovalId).toBe('approval')
})

it('충돌 후 이전 성공·동의를 지우고 재조회 전 저장을 막는다', async () => {
  api.mockResolvedValueOnce({ status: 'APPROVED', approval }).mockRejectedValueOnce(new Error('승인이 교체되었습니다.'))
  render(<AnswerApprovalPanel {...props} />)
  await userEvent.click(screen.getByRole('button', { name: '승인 상태 확인' }))
  await screen.findByText('확인 시점에 승인 유효')
  await userEvent.click(screen.getByRole('button', { name: '저장된 승인 철회' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('교체')
  expect(screen.queryByText('확인 시점에 승인 유효')).not.toBeInTheDocument()
  expect(screen.getByRole('checkbox')).not.toBeChecked()
  expect(screen.getByRole('button', { name: '이 요청 승인 저장 (전송 안 함)' })).toBeDisabled()
})

it('다른 묶음의 응답을 성공으로 표시하지 않는다', async () => {
  api.mockResolvedValue({ status: 'APPROVED', approval: { ...approval, bundleId: 'other' } })
  render(<AnswerApprovalPanel {...props} />)
  await userEvent.click(screen.getByRole('button', { name: '승인 상태 확인' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('승인 대상이 다릅니다')
})

it('요청 중 중복 동작을 막고 닫으면 대기를 중단한다', async () => {
  api.mockImplementation(() => new Promise(() => {}))
  const view = render(<AnswerApprovalPanel {...props} />)
  await userEvent.click(screen.getByRole('button', { name: '승인 상태 확인' }))
  await waitFor(() => expect(api).toHaveBeenCalledOnce())
  expect(screen.getByRole('button', { name: '승인 상태 확인' })).toBeDisabled()
  const signal = api.mock.calls[0][1]?.signal
  view.unmount()
  expect(signal?.aborted).toBe(true)
})
