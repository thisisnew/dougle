import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import PublicationPanel from './PublicationPanel'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const base = '/workspaces/one/knowledge/card'
const initial = { latestVersion: 2, review: null, publication: { revision: 1, version: 1, publishedAt: 'then' }, publishedEvidence: [{ freshness: 'CURRENT' }] }
const reviewed = { ...initial, review: { version: 2, reviewer: '검토자', note: '원문 대조', reviewedAt: 'now' } }
beforeEach(() => { api.mockReset() })

it('기본 동의 없이 검토를 저장하고 별도 게시 요청에 기대 버전과 이력을 보낸다', async () => {
  const user = userEvent.setup()
  api.mockResolvedValueOnce(initial).mockResolvedValueOnce(reviewed)
    .mockResolvedValueOnce({ ...reviewed, publication: { revision: 2, version: 2, publishedAt: 'now' } })
  render(<PublicationPanel base={base} version={2} disabled={false} onBusy={vi.fn()} onOpen={vi.fn()} />)
  expect(await screen.findByRole('button', { name: '사람 검토 기록 저장' })).toBeDisabled()
  for (const checkbox of screen.getAllByRole('checkbox')) expect(checkbox).not.toBeChecked()
  await user.type(screen.getByLabelText('검토자'), '검토자')
  await user.type(screen.getByLabelText('검토 기록'), '원문 대조')
  for (const checkbox of screen.getAllByRole('checkbox')) await user.click(checkbox)
  await user.click(screen.getByRole('button', { name: '사람 검토 기록 저장' }))
  expect(api).toHaveBeenCalledWith(`${base}/review`, expect.objectContaining({ method: 'POST',
    body: JSON.stringify({ expectedVersion: 2, reviewer: '검토자', note: '원문 대조', claimsSupported: true, uncertaintyRemoved: true }) }))
  expect(api.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)
  await user.click(await screen.findByRole('button', { name: '검토한 버전 게시' }))
  expect(api).toHaveBeenCalledWith(`${base}/publication`, expect.objectContaining({ method: 'POST',
    body: JSON.stringify({ expectedVersion: 2, expectedPublicationRevision: 1 }) }))
  expect(await screen.findByText('현재 게시본 v2 · 게시 이력 #2')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '검토한 버전 게시' })).toBeDisabled()
})

it('검토 실패 시 동의를 초기화하고 기록과 이전 게시본을 보존한다', async () => {
  const user = userEvent.setup()
  api.mockResolvedValueOnce(initial).mockRejectedValueOnce(new Error('근거 변경'))
  render(<PublicationPanel base={base} version={2} disabled={false} onBusy={vi.fn()} onOpen={vi.fn()} />)
  await user.type(await screen.findByLabelText('검토자'), '검토자')
  await user.type(screen.getByLabelText('검토 기록'), '입력 보존')
  for (const checkbox of screen.getAllByRole('checkbox')) await user.click(checkbox)
  await user.click(screen.getByRole('button', { name: '사람 검토 기록 저장' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('근거 변경')
  expect(screen.getByLabelText('검토 기록')).toHaveValue('입력 보존')
  for (const checkbox of screen.getAllByRole('checkbox')) expect(checkbox).not.toBeChecked()
  expect(screen.getByText('현재 게시본 v1 · 게시 이력 #1')).toBeInTheDocument()
})

it('이전 버전은 읽기 전용이며 오래된 게시 근거를 경고한다', async () => {
  const user = userEvent.setup()
  const open = vi.fn()
  api.mockResolvedValue({ ...initial, publishedEvidence: [{ freshness: 'MISSING' }] })
  render(<PublicationPanel base={base} version={1} disabled={false} onBusy={vi.fn()} onOpen={open} />)
  expect(await screen.findByText('이전 버전은 검토·게시할 수 없습니다. 최신 버전을 여세요.')).toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('게시본 근거가 변경·삭제')
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '게시본 열기' }))
  expect(open).toHaveBeenCalledWith(1)
})

it('게시 충돌은 기존 게시본을 유지하고 서버 오류를 알린다', async () => {
  const user = userEvent.setup()
  const busy = vi.fn()
  api.mockResolvedValueOnce(reviewed).mockRejectedValueOnce(new Error('게시 충돌'))
  render(<PublicationPanel base={base} version={2} disabled={false} onBusy={busy} onOpen={vi.fn()} />)
  await user.click(await screen.findByRole('button', { name: '검토한 버전 게시' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('게시 충돌')
  expect(screen.getByText('현재 게시본 v1 · 게시 이력 #1')).toBeInTheDocument()
  expect(busy.mock.calls).toEqual([[true], [false]])
})

it('화면 이탈 시 대기 요청을 중단한다', async () => {
  api.mockReturnValue(new Promise(() => {}))
  const view = render(<PublicationPanel base={base} version={2} disabled={false} onBusy={vi.fn()} onOpen={vi.fn()} />)
  await waitFor(() => expect(api).toHaveBeenCalledTimes(1))
  const signal = api.mock.calls[0][1]?.signal
  view.unmount()
  expect(signal?.aborted).toBe(true)
})
