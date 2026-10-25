import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import SourceSyncProgress from './SourceSyncProgress'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
beforeEach(() => api.mockReset())

it('서버의 실제 처리 개수를 표시하고 중단을 서버에 요청한다', async () => {
  api.mockResolvedValue({ stage: 'EXTRACTING', file: 'synthetic.txt', completed: 7, total: 12, cancellable: true })
  const cancelled = vi.fn()
  render(<SourceSyncProgress workspaceId="one" sourceId="source" onCancel={cancelled} />)
  await screen.findByText('synthetic.txt')
  expect(screen.getByRole('progressbar')).toHaveAttribute('value', '7')
  expect(screen.getByRole('progressbar')).toHaveAttribute('max', '12')
  await userEvent.click(screen.getByRole('button', { name: '중단' }))
  await waitFor(() => expect(cancelled).toHaveBeenCalledOnce())
  expect(api).toHaveBeenCalledWith('/workspaces/one/sources/source/cancel', { method: 'POST' })
})

it('전체 개수를 모르면 가짜 퍼센트를 표시하지 않고 관측만 해제한다', async () => {
  api.mockResolvedValue({ stage: 'READING', file: 'synthetic', completed: 8, total: 0, cancellable: true })
  const view = render(<SourceSyncProgress workspaceId="one" sourceId="source" onCancel={vi.fn()} />)
  await screen.findByText('synthetic')
  expect(screen.getByRole('progressbar')).not.toHaveAttribute('value')
  const signal = api.mock.calls[0][1]?.signal
  view.unmount()
  expect(signal?.aborted).toBe(true)
  expect(api.mock.calls.every(([path]) => !path.endsWith('/cancel'))).toBe(true)
})
