import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import GenerationComparison from './GenerationComparison'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const document = { card: { id: 'original', latestVersion: 2 }, version: { number: 1, title: '가상 문서', markdown: '생성 당시 내용',
  evidence: [{ snapshotId: 'snapshot', path: 'fixture.txt', startLine: 1, endLine: 1, quote: '가상 근거', freshness: 'CURRENT' }] } }
const comparison = { base: document, current: { ...document, version: { ...document.version, number: 2, markdown: '사람이 편집한 내용' } },
  generated: { ...document, card: { id: 'generated', latestVersion: 2 }, version: { ...document.version, markdown: '<script>alert(1)</script>' } } }
const props = { workspaceId: 'one', jobId: 'job', disabled: false, onOpen: vi.fn(), onClose: vi.fn() }
beforeEach(() => { api.mockReset(); props.onOpen.mockReset(); props.onClose.mockReset() })

it('세 문서와 편집 경고를 표시하며 HTML을 실행하지 않는다', async () => {
  api.mockResolvedValue(comparison)
  const { container } = render(<GenerationComparison {...props} />)
  expect(await screen.findByText(/생성 이후 원래 문서가 편집/)).toBeInTheDocument()
  expect(within(screen.getByLabelText('생성 당시 문서')).getByText('생성 당시 내용')).toBeInTheDocument()
  expect(within(screen.getByLabelText('현재 문서')).getByText('사람이 편집한 내용')).toBeInTheDocument()
  expect(within(screen.getByLabelText('AI 최초 결과')).getByText('<script>alert(1)</script>')).toBeInTheDocument()
  expect(screen.getByText(/최초 결과 v1을 비교/)).toBeInTheDocument()
  expect(container.querySelector('script')).toBeNull()
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
})

it('근거 삭제와 버전 미변경을 구별하고 현재 문서 열기는 저장하지 않는다', async () => {
  const user = userEvent.setup()
  api.mockResolvedValue({ ...comparison, current: document, generated: { ...comparison.generated, version: {
    ...document.version, evidence: [{ ...document.version.evidence[0], freshness: 'MISSING' }] } } })
  render(<GenerationComparison {...props} />)
  expect(await screen.findByText(/원래 문서의 버전은 생성 당시와 같습니다/)).toBeInTheDocument()
  expect(screen.getByText(/변경되거나 삭제된 근거/)).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '현재 문서 열기' }))
  expect(props.onOpen).toHaveBeenCalledWith('original')
  expect(api).toHaveBeenCalledTimes(1)
})

it('삭제·조회 오류를 표시하고 명시적 새로고침으로 복구한다', async () => {
  const user = userEvent.setup()
  api.mockRejectedValueOnce(new Error('지식을 찾을 수 없습니다')).mockResolvedValue(comparison)
  render(<GenerationComparison {...props} />)
  expect(await screen.findByRole('alert')).toHaveTextContent('지식을 찾을 수 없습니다')
  await user.click(screen.getByRole('button', { name: '비교 새로고침' }))
  expect(await screen.findByLabelText('생성 당시 문서')).toBeInTheDocument()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

it('편집 중 문서 이동을 막고 비교 닫기는 허용한다', async () => {
  api.mockResolvedValue(comparison)
  render(<GenerationComparison {...props} disabled />)
  expect(await screen.findByRole('button', { name: '현재 문서 열기' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'AI 결과의 최신 문서 열기' })).toBeDisabled()
  await userEvent.click(screen.getByRole('button', { name: '비교 닫기' }))
  expect(props.onClose).toHaveBeenCalledOnce()
})

it('작업 전환·종료는 이전 조회를 취소한다', async () => {
  api.mockReturnValue(new Promise(() => {}))
  const view = render(<GenerationComparison {...props} />)
  await waitFor(() => expect(api).toHaveBeenCalledTimes(1))
  const firstSignal = api.mock.calls[0][1]!.signal as AbortSignal
  view.rerender(<GenerationComparison {...props} jobId="next" />)
  expect(firstSignal.aborted).toBe(true)
  await waitFor(() => expect(api).toHaveBeenCalledTimes(2))
  view.unmount()
  expect((api.mock.calls[1][1]!.signal as AbortSignal).aborted).toBe(true)
})

it('처리 기록을 명시적으로 열기 전에는 추가 조회나 저장을 하지 않는다', async () => {
  api.mockResolvedValueOnce(comparison).mockResolvedValueOnce([]).mockResolvedValueOnce({ status: 'OPEN' })
  render(<GenerationComparison {...props} />)
  await screen.findByLabelText('생성 당시 문서')
  expect(api).toHaveBeenCalledTimes(1)
  await userEvent.click(screen.getByRole('button', { name: '제안 처리 기록 열기' }))
  expect(await screen.findByText('처리 상태: 검토 대기')).toBeInTheDocument()
  expect(api.mock.calls[1][0]).toBe('/workspaces/one/generation-jobs/job/decisions')
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
})

it('전체 채택 검토는 명시적으로 열며 사람 편집이 있으면 저장을 차단한다', async () => {
  api.mockResolvedValueOnce(comparison).mockResolvedValueOnce({ status: 'OPEN', decisionRevision: 0, acceptance: null })
  render(<GenerationComparison {...props} />)
  await screen.findByLabelText('생성 당시 문서')
  expect(api).toHaveBeenCalledTimes(1)
  await userEvent.click(screen.getByRole('button', { name: '전체 채택 검토' }))
  expect(await screen.findByRole('button', { name: '전체 결과를 새 초안으로 채택' })).toBeDisabled()
  expect(screen.getByText(/생성 후 문서 편집 또는 근거 변경/)).toBeInTheDocument()
})
