import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import InstalledCliChoices from './InstalledCliChoices'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
beforeEach(() => api.mockReset())

it.each(['CLAUDE', 'CODEX'] as const)('selects %s without path typing and preserves the other provider', async provider => {
  const candidate = { name: `${provider.toLowerCase()}-work`, path: `/synthetic/bin/${provider.toLowerCase()}-work` }
  api.mockResolvedValueOnce([candidate]).mockResolvedValueOnce({ saved: { codexPath: '/other/codex', claudePath: '/other/claude', revision: 4 } })
    .mockResolvedValueOnce({ restartRequired: true })
  render(<InstalledCliChoices provider={provider} unavailable disabled={false} onPendingChange={vi.fn()} />)
  expect(await screen.findByRole('button', { name: `${candidate.name} 사용` })).toBeVisible()
  expect(api.mock.calls.every(([, options]) => options?.method !== 'PUT')).toBe(true)
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: `${candidate.name} 사용` }))
  expect(await screen.findByText(/앱을 다시 열면 적용/)).toBeVisible()
  const body = JSON.parse(api.mock.calls.find(([, options]) => options?.method === 'PUT')![1]!.body as string)
  expect(body).toEqual({ codexPath: provider === 'CODEX' ? candidate.path : '/other/codex',
    claudePath: provider === 'CLAUDE' ? candidate.path : '/other/claude', expectedRevision: 4, acknowledged: true })
})

it('does not claim connection when discovery is empty or fails', async () => {
  api.mockResolvedValueOnce([])
  const view = render(<InstalledCliChoices provider="CLAUDE" unavailable disabled={false} onPendingChange={vi.fn()} />)
  expect(await screen.findByText(/AI 실행 도구를 찾지 못했습니다/)).toBeVisible()
  api.mockRejectedValueOnce(new Error('offline'))
  view.rerender(<InstalledCliChoices provider="CODEX" unavailable disabled={false} onPendingChange={vi.fn()} />)
  expect(await screen.findByRole('alert')).toHaveTextContent('목록을 확인하지 못했습니다')
  expect(api.mock.calls.every(([, options]) => options?.method !== 'PUT')).toBe(true)
})

it('ignores a stale discovery response after provider change', async () => {
  let finish!: (value: unknown) => void
  api.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const view = render(<InstalledCliChoices provider="CLAUDE" unavailable disabled={false} onPendingChange={vi.fn()} />)
  api.mockResolvedValueOnce([{ name: 'codex-team', path: '/fixture/codex-team' }])
  view.rerender(<InstalledCliChoices provider="CODEX" unavailable disabled={false} onPendingChange={vi.fn()} />)
  await screen.findByRole('button', { name: 'codex-team 사용' })
  finish([{ name: 'claude-work', path: '/fixture/claude-work' }])
  await waitFor(() => expect(screen.queryByRole('button', { name: 'claude-work 사용' })).not.toBeInTheDocument())
})

it('shows save conflicts without claiming activation', async () => {
  api.mockResolvedValueOnce([{ name: 'claude-work', path: '/fixture/claude-work' }])
    .mockResolvedValueOnce({ saved: { codexPath: '', claudePath: '', revision: 4 } })
    .mockRejectedValueOnce(new Error('다른 설정이 저장되었습니다.'))
  render(<InstalledCliChoices provider="CLAUDE" unavailable disabled={false} onPendingChange={vi.fn()} />)
  await userEvent.click(await screen.findByRole('button', { name: 'claude-work 사용' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('다른 설정')
  expect(screen.queryByText(/선택했습니다/)).not.toBeInTheDocument()
})
