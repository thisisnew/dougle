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
    .mockResolvedValueOnce({ provider, state: 'AUTHENTICATED', remoteConnection: 'NOT_CHECKED' })
  const connected = vi.fn()
  render(<InstalledCliChoices provider={provider} unavailable disabled={false} onPendingChange={vi.fn()} onConnectionResult={connected} />)
  expect(await screen.findByRole('button', { name: new RegExp(`${candidate.name} 사용`) })).toBeVisible()
  expect(api.mock.calls.every(([, options]) => options?.method !== 'PUT')).toBe(true)
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: new RegExp(`${candidate.name} 사용`) }))
  expect(await screen.findByText('로그인 상태를 확인했습니다.')).toBeVisible()
  const call = api.mock.calls.find(([, options]) => options?.method === 'POST')!
  expect(call[0]).toBe(`/ai/launch-settings/connect/${provider}`)
  expect(JSON.parse(call[1]!.body as string)).toEqual({ path: candidate.path, expectedRevision: 4 })
  expect(connected).toHaveBeenCalledWith(expect.objectContaining({ provider, state: 'AUTHENTICATED' }))
  expect(screen.queryByText(/다시 열면/)).not.toBeInTheDocument()
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
  await screen.findByRole('button', { name: /codex-team 사용/ })
  finish([{ name: 'claude-work', path: '/fixture/claude-work' }])
  await waitFor(() => expect(screen.queryByRole('button', { name: 'claude-work 사용' })).not.toBeInTheDocument())
})

it('shows save conflicts without claiming activation', async () => {
  api.mockResolvedValueOnce([{ name: 'claude-work', path: '/fixture/claude-work' }])
    .mockResolvedValueOnce({ saved: { codexPath: '', claudePath: '', revision: 4 } })
    .mockRejectedValueOnce(new Error('다른 설정이 저장되었습니다.'))
  render(<InstalledCliChoices provider="CLAUDE" unavailable disabled={false} onPendingChange={vi.fn()} />)
  await userEvent.click(await screen.findByRole('button', { name: /claude-work 사용/ }))
  expect(await screen.findByRole('alert')).toHaveTextContent('다른 설정')
  expect(screen.queryByText(/선택했습니다/)).not.toBeInTheDocument()
})

it.each(['CHECK_FAILED', 'TOOL_UNAVAILABLE', 'UNSUPPORTED'])('never claims successful activation for %s', async state => {
  api.mockResolvedValueOnce([{ name: 'claude-work', path: 'dougle-alias-fixture' }])
    .mockResolvedValueOnce({ saved: { revision: 2 } })
    .mockResolvedValueOnce({ provider: 'CLAUDE', state, remoteConnection: 'NOT_CHECKED' })
  render(<InstalledCliChoices provider="CLAUDE" unavailable disabled={false} onPendingChange={vi.fn()} />)
  await userEvent.click(await screen.findByRole('button', { name: /claude-work 사용/ }))
  expect(await screen.findByRole('alert')).toHaveTextContent('기존 설정을 유지')
  expect(screen.queryByText('로그인 상태를 확인했습니다.')).not.toBeInTheDocument()
})

it('deduplicates paths but distinguishes accounts and installations', async () => {
  const native = { name: 'claude', path: '/Users/synthetic/.local/bin/claude' }
  api.mockResolvedValueOnce([native, native, { name: 'claude', path: '/opt/homebrew/bin/claude' }, { name: 'claude-work', path: 'dougle-alias-fixture' }])
  render(<InstalledCliChoices provider="CLAUDE" unavailable disabled={false} onPendingChange={vi.fn()} />)
  expect(await screen.findAllByRole('button', { name: /claude 사용/ })).toHaveLength(2)
  expect(screen.getByRole('button', { name: /claude 사용 Homebrew 설치/ })).toBeVisible()
  expect(screen.getByRole('button', { name: /claude-work 사용 터미널 계정 설정/ })).toBeVisible()
  expect(screen.getByText('~/.local/bin/claude')).toBeVisible()
})

it('does not accept a different provider or malformed connection response', async () => {
  api.mockResolvedValueOnce([{ name: 'claude-work', path: 'dougle-alias-fixture' }])
    .mockResolvedValueOnce({ saved: { revision: 2 } })
    .mockResolvedValueOnce({ provider: 'CODEX', state: 'AUTHENTICATED', remoteConnection: 'NOT_CHECKED' })
  const result = vi.fn()
  render(<InstalledCliChoices provider="CLAUDE" unavailable disabled={false} onPendingChange={vi.fn()} onConnectionResult={result} />)
  await userEvent.click(await screen.findByRole('button', { name: /claude-work 사용/ }))
  expect(await screen.findByRole('alert')).toHaveTextContent('연결 확인 결과가 다릅니다')
  expect(result).not.toHaveBeenCalled()
})

it('ignores a late selection result after the view is closed', async () => {
  let finish!: (value: unknown) => void
  api.mockResolvedValueOnce([{ name: 'claude-work', path: 'dougle-alias-fixture' }])
    .mockResolvedValueOnce({ saved: { revision: 2 } })
    .mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const result = vi.fn()
  const view = render(<InstalledCliChoices provider="CLAUDE" unavailable disabled={false} onPendingChange={vi.fn()} onConnectionResult={result} />)
  await userEvent.click(await screen.findByRole('button', { name: /claude-work 사용/ }))
  expect(await screen.findByText(/로그인 상태를 확인하는 중/)).toBeVisible()
  expect(screen.getByRole('button', { name: /claude-work 사용/ })).toBeDisabled()
  view.unmount()
  finish({ provider: 'CLAUDE', state: 'AUTHENTICATED', remoteConnection: 'NOT_CHECKED' })
  await waitFor(() => expect((api.mock.calls.at(-1)![1]!.signal as AbortSignal).aborted).toBe(true))
  expect(result).not.toHaveBeenCalled()
})
