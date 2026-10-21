import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import EvidenceAnswerPreparation from './EvidenceAnswerPreparation'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const props = { workspaceId: 'one', bundleId: 'bundle', bundleDigest: 'frozen-hash' }
const validation = { bundleId: 'bundle', digest: 'frozen-hash', checkedAt: '2026-10-21T00:00:00Z', eligible: true, status: 'CURRENT',
  items: [{ label: 'R1', status: 'CURRENT', currentEntryId: 'file', currentSourceRevision: 'new-revision', supports: [] }] }
const preview = { bundleId: 'bundle', bundleDigest: 'frozen-hash', provider: 'CODEX', requestedModel: 'model-one', digest: 'request-hash',
  prompt: 'Untrusted evidence: <script>bad()</script>', characters: 44, maxCharacters: 20000, validation }
beforeEach(() => { api.mockReset() })

it('자동 검사나 전송 없이 명시적 재검증 결과와 시각을 표시한다', async () => {
  api.mockResolvedValue(validation)
  render(<EvidenceAnswerPreparation {...props} />)
  expect(api).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('button', { name: '근거 상태 다시 확인' }))
  expect(await screen.findByLabelText('근거 재검증 결과')).toHaveTextContent('현재 수집본과 일치')
  expect(screen.getByText(/현재 Source 버전 new-revision/)).toBeInTheDocument()
  expect(api).toHaveBeenCalledWith('/workspaces/one/evidence-bundles/bundle/validation', expect.objectContaining({ signal: expect.any(AbortSignal) }))
})

it('모델 선택을 요청에 담고 전문을 안전하게 표시하며 조건 변경 시 폐기한다', async () => {
  const user = userEvent.setup()
  api.mockResolvedValue(preview)
  render(<EvidenceAnswerPreparation {...props} />)
  await user.selectOptions(screen.getByLabelText('답변 모델 선택'), 'custom')
  await user.type(screen.getByLabelText('답변 모델 ID'), 'model-one')
  await user.click(screen.getByRole('button', { name: '요청 미리보기 만들기 (전송 안 함)' }))
  expect(await screen.findByLabelText('답변 요청 미리보기')).toHaveTextContent('model-one')
  const call = api.mock.calls[0]
  expect(call[0]).toBe('/workspaces/one/evidence-bundles/bundle/answer-preview')
  expect(JSON.parse(call[1]!.body as string)).toEqual({ provider: 'CODEX', requestedModel: 'model-one' })
  expect(screen.getByText(preview.prompt)).toBeInTheDocument()
  expect(document.querySelector('script')).toBeNull()
  expect(screen.getByRole('checkbox')).not.toBeChecked()
  expect(screen.getByRole('button', { name: '이 요청 승인 저장 (전송 안 함)' })).toBeDisabled()
  await user.selectOptions(screen.getByLabelText('답변 제공자'), 'CLAUDE')
  expect(screen.queryByLabelText('답변 요청 미리보기')).not.toBeInTheDocument()
  expect(screen.queryByLabelText('근거 재검증 결과')).not.toBeInTheDocument()
})

it('변경된 출처를 번호별로 표시하고 무결과도 준비 완료로 취급하지 않는다', async () => {
  api.mockResolvedValue({ ...validation, status: 'BLOCKED', eligible: false,
    items: [{ label: 'R1', status: 'SUPPORT_CHANGED', supports: [{ label: 'E1', status: 'MISSING' }] }] })
  render(<EvidenceAnswerPreparation {...props} />)
  await userEvent.click(screen.getByRole('button', { name: '근거 상태 다시 확인' }))
  expect(await screen.findByLabelText('근거 재검증 결과')).toHaveTextContent('[R1/E1] 자료 삭제·조회 불가')
  api.mockResolvedValue({ ...validation, status: 'NO_EVIDENCE', eligible: false, items: [] })
  await userEvent.click(screen.getByRole('button', { name: '근거 상태 다시 확인' }))
  expect(await screen.findByText('사용 가능한 근거 없음')).toBeInTheDocument()
})

it('미리보기 오류나 식별 불일치 후 기존 성공 상태를 남기지 않는다', async () => {
  api.mockResolvedValueOnce(preview).mockRejectedValueOnce(new Error('근거가 변경되었습니다.'))
    .mockResolvedValueOnce({ ...validation, digest: 'wrong' })
  render(<EvidenceAnswerPreparation {...props} />)
  const button = screen.getByRole('button', { name: '요청 미리보기 만들기 (전송 안 함)' })
  await userEvent.click(button)
  await screen.findByLabelText('답변 요청 미리보기')
  await userEvent.click(button)
  expect(await screen.findByRole('alert')).toHaveTextContent('근거가 변경되었습니다.')
  expect(screen.queryByLabelText('답변 요청 미리보기')).not.toBeInTheDocument()
  expect(screen.queryByLabelText('근거 재검증 결과')).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '근거 상태 다시 확인' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('식별 정보가 다릅니다')
})

it('서버 기본값과 CLI 기본값을 구분하고 빈 직접 입력은 요청하지 않는다', async () => {
  const user = userEvent.setup()
  api.mockResolvedValue(preview)
  render(<EvidenceAnswerPreparation {...props} />)
  const button = screen.getByRole('button', { name: '요청 미리보기 만들기 (전송 안 함)' })
  await user.click(button)
  await screen.findByLabelText('답변 요청 미리보기')
  expect(JSON.parse(api.mock.calls[0][1]!.body as string).requestedModel).toBeNull()
  await user.selectOptions(screen.getByLabelText('답변 모델 선택'), 'cli')
  expect(screen.queryByLabelText('답변 요청 미리보기')).not.toBeInTheDocument()
  await user.click(button)
  await screen.findByLabelText('답변 요청 미리보기')
  expect(JSON.parse(api.mock.calls[1][1]!.body as string).requestedModel).toBe('')
  await user.selectOptions(screen.getByLabelText('답변 모델 선택'), 'custom')
  await user.click(button)
  expect(await screen.findByRole('alert')).toHaveTextContent('모델 ID를 입력하세요.')
  expect(api).toHaveBeenCalledTimes(2)
})

it('요청 중 중복 동작과 조건 변경을 막고 닫으면 취소한다', async () => {
  api.mockImplementation(() => new Promise(() => {}))
  const view = render(<EvidenceAnswerPreparation {...props} />)
  await userEvent.click(screen.getByRole('button', { name: '근거 상태 다시 확인' }))
  await waitFor(() => expect(api).toHaveBeenCalledOnce())
  expect(screen.getByLabelText('답변 제공자')).toBeDisabled()
  expect(screen.getByRole('button', { name: '요청 미리보기 만들기 (전송 안 함)' })).toBeDisabled()
  const signal = api.mock.calls[0][1]?.signal
  view.unmount()
  expect(signal?.aborted).toBe(true)
})
