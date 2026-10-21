import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import GenerationJobsPanel from './GenerationJobsPanel'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const job = { id: 'job', provider: 'CODEX', status: 'RUNNING', resultCardId: null, error: null, createdAt: '2026-10-21T10:00:00Z' }
beforeEach(() => api.mockReset())

it('화면을 다시 열면 서버 작업 목록을 복원하고 명시적 취소 API를 호출한다', async () => {
  const user = userEvent.setup()
  let cancelled = false
  api.mockImplementation(async (_path, options) => {
    if (options?.method === 'POST') { cancelled = true; return { ...job, status: 'CANCELLED' } }
    return [{ ...job, status: cancelled ? 'CANCELLED' : 'RUNNING' }]
  })
  render(<GenerationJobsPanel workspaceId="one" revision={0} onOpen={vi.fn()} />)
  await user.click(await screen.findByRole('button', { name: '생성 중단' }))
  expect(api).toHaveBeenCalledWith('/workspaces/one/generation-jobs/job/cancel', expect.objectContaining({ method: 'POST' }))
  await waitFor(() => expect(screen.getByText(/CODEX · 취소됨/)).toBeInTheDocument())
  expect(screen.queryByRole('button', { name: '생성 중단' })).not.toBeInTheDocument()
})

it('완료된 작업의 별도 초안을 열고 실패·재시작 중단을 숨기지 않는다', async () => {
  const user = userEvent.setup()
  const open = vi.fn()
  api.mockResolvedValue([{ ...job, status: 'SUCCEEDED', resultCardId: 'new-card' },
    { ...job, id: 'failed', status: 'FAILED', error: '로그인이 필요합니다' },
    { ...job, id: 'interrupted', status: 'INTERRUPTED' }])
  render(<GenerationJobsPanel workspaceId="one" revision={0} onOpen={open} />)
  await user.click(await screen.findByRole('button', { name: '생성된 초안 열기' }))
  expect(open).toHaveBeenCalledWith('new-card')
  expect(screen.getByText('로그인이 필요합니다')).toBeInTheDocument()
  expect(screen.getByText(/앱 재시작으로 중단됨/)).toBeInTheDocument()
  expect(api.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false)
})

it('조회 실패는 새로고침으로 복구하며 화면 종료는 작업 취소가 아니다', async () => {
  const user = userEvent.setup()
  api.mockRejectedValueOnce(new Error('연결 실패')).mockResolvedValue([job])
  const view = render(<GenerationJobsPanel workspaceId="one" revision={0} onOpen={vi.fn()} />)
  expect(await screen.findByRole('alert')).toHaveTextContent('연결 실패')
  await user.click(screen.getByRole('button', { name: '작업 새로고침' }))
  await screen.findByRole('button', { name: '생성 중단' })
  view.unmount()
  expect(api.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false)
  expect((api.mock.calls[1][1]!.signal as AbortSignal).aborted).toBe(true)
})
