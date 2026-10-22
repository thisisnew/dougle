import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import CliLaunchPanel from './CliLaunchPanel'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const initial = { saved: { codexPath: '', claudePath: '', revision: 0 }, activeCodexCommand: 'codex', activeClaudeCommand: 'claude', restartRequired: false }
beforeEach(() => {
  api.mockReset()
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
})

it('조회나 경로 입력으로 실행하지 않고 명시적 확인 후 저장한다', async () => {
  api.mockResolvedValueOnce(initial)
  api.mockResolvedValueOnce({ ...initial, saved: { ...initial.saved, codexPath: '/synthetic/codex', revision: 1 }, restartRequired: true })
  render(<CliLaunchPanel onClose={vi.fn()} />)
  await userEvent.type(await screen.findByRole('textbox', { name: 'Codex 절대 경로' }), '/synthetic/codex')
  expect(screen.getByRole('button', { name: '경로 저장' })).toBeDisabled()
  expect(api).toHaveBeenCalledTimes(1)
  await userEvent.click(screen.getByRole('checkbox'))
  await userEvent.click(screen.getByRole('button', { name: '경로 저장' }))
  expect(await screen.findByText(/저장된 경로는 아직 적용되지/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'CLI 설치 확인' })).toBeDisabled()
  expect(api).toHaveBeenCalledWith('/ai/launch-settings', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ codexPath: '/synthetic/codex', claudePath: '', expectedRevision: 0, acknowledged: true }) }))
  expect(api.mock.calls.some(([path]) => path.includes('/probe'))).toBe(false)
})

it('설정 충돌을 표시하고 다시 불러오면 새 revision을 사용한다', async () => {
  api.mockResolvedValueOnce(initial)
  api.mockRejectedValueOnce(new Error('다른 설정이 저장되었습니다.'))
  api.mockResolvedValueOnce({ ...initial, saved: { ...initial.saved, revision: 5 } })
  render(<CliLaunchPanel onClose={vi.fn()} />)
  await userEvent.click(await screen.findByRole('checkbox'))
  await userEvent.click(screen.getByRole('button', { name: '경로 저장' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('다른 설정')
  await userEvent.click(screen.getByRole('button', { name: '설정 다시 불러오기' }))
  await waitFor(() => expect(screen.getByRole('checkbox')).not.toBeChecked())
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

it('입력을 바꾸면 신뢰 확인을 다시 요구하고 닫을 때 요청을 취소한다', async () => {
  api.mockResolvedValue(initial)
  const view = render(<CliLaunchPanel onClose={vi.fn()} />)
  await userEvent.click(await screen.findByRole('checkbox'))
  await userEvent.type(screen.getByRole('textbox', { name: 'Claude 절대 경로' }), '/synthetic/claude')
  expect(screen.getByRole('checkbox')).not.toBeChecked()
  const signal = api.mock.calls[0][1]?.signal
  view.unmount()
  expect(signal?.aborted).toBe(true)
})
