import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ProposalMerge from './ProposalMerge'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const evidence = [{ snapshotId: 'snapshot', path: 'fixture', startLine: 1, endLine: 1, quote: 'evidence', freshness: 'CURRENT' }]
const original = { card: { id: 'card', latestVersion: 2 }, version: { number: 2, title: 'Human title', markdown: 'Human text [E1]', evidence } }
const comparison = { base: { ...original, version: { ...original.version, number: 1 } }, current: original,
  generated: { card: { id: 'result', latestVersion: 1 }, version: { ...original.version, number: 1, markdown: 'Useful AI sentence [E1]\nUnwanted sentence' } } }
const open = { status: 'OPEN', decisionRevision: 2, acceptance: null }
const accepted = { ...open, status: 'ACCEPTED', acceptance: { appliedVersion: 3, mode: 'MANUAL' } }
const props = { workspaceId: 'one', jobId: 'job', onClose: vi.fn() }

beforeEach(() => {
  api.mockReset(); props.onClose.mockReset()
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
})
afterEach(() => { vi.restoreAllMocks() })

async function edit() {
  await screen.findByLabelText('반영할 Markdown')
  await userEvent.type(screen.getByLabelText('반영할 Markdown'), '\nchosen text')
  await userEvent.type(screen.getByLabelText('부분 반영 이유'), 'selected reason')
  await userEvent.click(screen.getByRole('checkbox'))
}

it('현재 문서에서 시작하고 선택한 AI 문장만 추가해 수동 채택한다', async () => {
  api.mockResolvedValueOnce(comparison).mockResolvedValueOnce(open).mockResolvedValueOnce(accepted)
  render(<ProposalMerge {...props} />)
  expect(await screen.findByLabelText('반영할 Markdown')).toHaveValue('Human text [E1]')
  expect(screen.getByRole('button', { name: '편집한 내용을 새 초안으로 반영' })).toBeDisabled()
  const source = screen.getByLabelText('선택할 AI 원문') as HTMLTextAreaElement
  source.setSelectionRange(0, 'Useful AI sentence [E1]'.length)
  fireEvent.select(source)
  await userEvent.click(screen.getByRole('button', { name: '선택한 AI 문장 추가' }))
  expect(screen.getByLabelText('반영할 Markdown')).toHaveValue('Human text [E1]\n\nUseful AI sentence [E1]')
  await userEvent.type(screen.getByLabelText('부분 반영 이유'), 'selected reason')
  await userEvent.click(screen.getByRole('checkbox'))
  await userEvent.click(screen.getByRole('button', { name: '편집한 내용을 새 초안으로 반영' }))
  expect(await screen.findByText(/이미 채택 완료 · 원래 문서 v3/)).toBeInTheDocument()
  expect(JSON.parse(api.mock.calls[2][1]!.body as string)).toEqual({
    expectedRevision: 2, expectedSourceVersion: 2, expectedResultVersion: 1, title: 'Human title',
    markdown: 'Human text [E1]\n\nUseful AI sentence [E1]', note: 'selected reason', confirmed: true,
  })
  expect(api.mock.calls[2][0]).toBe('/workspaces/one/generation-jobs/job/acceptance/merge')
  await userEvent.click(screen.getByRole('button', { name: '편집 닫기' }))
  expect(props.onClose).toHaveBeenCalledWith(true)
})

it('충돌 후 재조회는 편집 내용을 보존하고 최신 버전 확인을 다시 요구한다', async () => {
  api.mockResolvedValueOnce(comparison).mockResolvedValueOnce(open).mockRejectedValueOnce(new Error('version conflict'))
    .mockResolvedValueOnce({ ...comparison, current: { ...original, version: { ...original.version, number: 3, markdown: 'Other editor [E1]' } } })
    .mockResolvedValueOnce(open)
  render(<ProposalMerge {...props} />)
  await edit()
  await userEvent.click(screen.getByRole('button', { name: '편집한 내용을 새 초안으로 반영' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('version conflict')
  expect(screen.getByRole('checkbox')).not.toBeChecked()
  await userEvent.click(screen.getByRole('button', { name: '최신 비교 다시 불러오기' }))
  await screen.findByText('현재 문서 v3')
  expect(screen.getByLabelText('반영할 Markdown')).toHaveValue('Human text [E1]\nchosen text')
  expect(screen.getByText('Other editor [E1]')).toBeInTheDocument()
  expect(screen.getByLabelText('부분 반영 이유')).toHaveValue('selected reason')
  expect(screen.getByRole('checkbox')).not.toBeChecked()
  expect(api).toHaveBeenCalledTimes(5)
})

it('미저장 편집 닫기·Escape에는 폐기 확인을 요구하고 페이지 종료를 경고한다', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  api.mockResolvedValueOnce(comparison).mockResolvedValueOnce(open)
  render(<ProposalMerge {...props} />)
  await edit()
  await userEvent.click(screen.getByRole('button', { name: '편집 닫기' }))
  expect(confirm).toHaveBeenCalledOnce()
  expect(props.onClose).not.toHaveBeenCalled()
  const unload = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(unload)
  expect(unload.defaultPrevented).toBe(true)
  confirm.mockReturnValue(true)
  fireEvent(screen.getByRole('dialog'), new Event('cancel', { bubbles: true, cancelable: true }))
  expect(props.onClose).toHaveBeenCalledWith(false)
})

it('근거 연결이 다른 제안은 저장하지 않는다', async () => {
  api.mockResolvedValueOnce({ ...comparison, current: { ...original, version: { ...original.version,
    evidence: [{ ...evidence[0], snapshotId: 'different' }] } } }).mockResolvedValueOnce(open)
  render(<ProposalMerge {...props} />)
  await edit()
  expect(screen.getByRole('alert')).toHaveTextContent('근거 연결이 다르거나')
  expect(screen.getByRole('button', { name: '편집한 내용을 새 초안으로 반영' })).toBeDisabled()
})

it('다른 창이 채택해도 작성하던 본문을 보존하고 닫기 확인을 요구한다', async () => {
  api.mockResolvedValueOnce(comparison).mockResolvedValueOnce(open).mockResolvedValueOnce(comparison).mockResolvedValueOnce(accepted)
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  render(<ProposalMerge {...props} />)
  await edit()
  await userEvent.click(screen.getByRole('button', { name: '최신 비교 다시 불러오기' }))
  await screen.findByText(/이미 채택 완료/)
  expect(screen.getByLabelText('반영할 Markdown')).toHaveValue('Human text [E1]\nchosen text')
  await userEvent.click(screen.getByRole('button', { name: '편집 닫기' }))
  expect(confirm).toHaveBeenCalledOnce()
  expect(props.onClose).not.toHaveBeenCalled()
})

it('저장 중 닫기·재조회·중복 저장을 차단하고 종료 시 요청을 취소한다', async () => {
  api.mockResolvedValueOnce(comparison).mockResolvedValueOnce(open).mockReturnValueOnce(new Promise(() => {}))
  const view = render(<ProposalMerge {...props} />)
  await edit()
  await userEvent.click(screen.getByRole('button', { name: '편집한 내용을 새 초안으로 반영' }))
  expect(screen.getByRole('button', { name: '편집 닫기' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '최신 비교 다시 불러오기' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '부분 반영 저장 중…' })).toBeDisabled()
  await waitFor(() => expect(api).toHaveBeenCalledTimes(3))
  const signal = api.mock.calls[2][1]!.signal as AbortSignal
  view.unmount()
  expect(signal.aborted).toBe(true)
})
