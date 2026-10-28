import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ProviderConnectionPanel from './ProviderConnectionPanel'
import GenerationPanel from './GenerationPanel'
import { preferredProvider } from './providerPreference'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
it.each(['CODEX', 'CLAUDE'] as const)('offers trusted company executable configuration for %s', async provider => {
  localStorage.setItem('forge.ai.provider', provider)
  api.mockImplementation(async path => path === '/ai/launch-settings'
    ? { saved: { codexPath: '', claudePath: '', revision: 0 }, activeCodexCommand: 'codex', activeClaudeCommand: 'claude', restartRequired: false }
    : { provider, state: 'TOOL_UNAVAILABLE', checkedAt: '', remoteConnection: 'NOT_CHECKED' })
  render(<ProviderConnectionPanel onClose={vi.fn()} />)
  await userEvent.click(screen.getByRole('button', { name: '연결' }))
  expect(await screen.findByText(/회사.*실행 도구를 사용한다면/)).toBeVisible()
  await userEvent.click(screen.getByRole('button', { name: '회사용 AI 실행 설정' }))
  expect(await screen.findByLabelText('Codex 실행 명령 또는 경로')).toBeVisible()
  expect(screen.getByLabelText('Claude 실행 명령 또는 경로')).toBeVisible()
  expect(screen.getByLabelText('진단 대상')).toHaveValue(provider)
  expect(screen.getByRole('button', { name: '경로 저장' })).toBeDisabled()
  expect(api.mock.calls.every(([, options]) => options?.method !== 'PUT')).toBe(true)
})
beforeEach(() => {
  api.mockReset(); localStorage.clear()
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
})
afterEach(() => { localStorage.clear() })

it('연결 창을 열고 같은 제공자를 누르거나 모델을 바꿔도 로그인을 해제하지 않는다', async () => {
  localStorage.setItem('forge.ai.provider', 'CLAUDE')
  const changed = vi.fn()
  render(<ProviderConnectionPanel onClose={vi.fn()} onConnectionChange={changed} initialConnection={{provider:'CODEX',state:'AUTHENTICATED',model:'gpt-6.1-sol'}} />)
  expect(screen.getByRole('button', {name:'연결됨'})).toBeDisabled()
  expect(screen.queryByText(/앱에 포함|별도 도구 필요/)).not.toBeInTheDocument()
  expect(screen.getByText('OpenAI')).toBeVisible()
  expect(screen.getByText('Anthropic')).toBeVisible()
  await userEvent.click(screen.getByRole('button', {name:/Codex/}))
  await userEvent.selectOptions(screen.getByRole('combobox'), 'gpt-6-luna')
  expect(screen.getByRole('button', {name:'연결'})).toBeEnabled()
  expect(changed).toHaveBeenLastCalledWith({provider:'CODEX',state:'AUTHENTICATED',model:'gpt-6.1-sol'})
  expect(changed.mock.calls.every(([value]) => value.state === 'AUTHENTICATED')).toBe(true)
  expect(api).not.toHaveBeenCalled()
})

it('다른 제공자를 살펴보고 돌아와도 기존 연결을 유지한다', async () => {
  const changed = vi.fn()
  render(<ProviderConnectionPanel onClose={vi.fn()} onConnectionChange={changed} initialConnection={{provider:'CODEX',state:'AUTHENTICATED'}} />)
  await userEvent.click(screen.getByRole('button', {name:/Claude/}))
  expect(changed.mock.calls.every(([value]) => value.provider === 'CODEX' && value.state === 'AUTHENTICATED')).toBe(true)
  expect(preferredProvider()).toBe('CODEX')
  await userEvent.click(screen.getByRole('button', {name:/Codex/}))
  expect(screen.getByRole('button', {name:'연결됨'})).toBeDisabled()
  expect(api).not.toHaveBeenCalled()
})

it('상단에서 열면 자료 전송 없이 로그인 상태만 자동 확인한다', async () => {
  api.mockResolvedValue({ provider: 'CODEX', state: 'AUTHENTICATED', checkedAt: 'time', remoteConnection: 'NOT_CHECKED' })
  render(<ProviderConnectionPanel checkOnOpen onClose={vi.fn()} />)
  expect(await screen.findByRole('button', { name: '연결됨' })).toBeDisabled()
  expect(screen.queryByText(/Codex · 로그인 확인/)).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(1)
  expect(api).toHaveBeenCalledWith('/ai/providers/CODEX/connection', expect.objectContaining({ method: 'POST' }))
})

it('모델 선택을 저장하고 연결 성공 버튼은 비활성화한다', async () => {
  api.mockResolvedValue({ provider: 'CODEX', state: 'AUTHENTICATED', checkedAt: 'time', remoteConnection: 'NOT_CHECKED' })
  const changed = vi.fn()
  render(<ProviderConnectionPanel onClose={vi.fn()} onConnectionChange={changed} />)
  expect(Array.from((screen.getByLabelText('모델') as HTMLSelectElement).options).map(option => option.text)).toEqual(['GPT-6.1 Sol', 'GPT-6 Luna', 'GPT-6 Astra'])
  expect(screen.queryByText('모델 ID 직접 지정')).not.toBeInTheDocument()
  await userEvent.selectOptions(screen.getByLabelText('모델'), 'gpt-6-luna')
  expect(api).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('button', { name: '연결' }))
  expect(await screen.findByRole('button', { name: '연결됨' })).toBeDisabled()
  expect(changed).toHaveBeenLastCalledWith({ provider: 'CODEX', state: 'AUTHENTICATED', model: 'gpt-6-luna' })
  expect(JSON.parse(localStorage.getItem('forge.ai.model.CODEX')!)).toEqual({ mode: 'custom', model: 'gpt-6-luna' })
  await userEvent.selectOptions(screen.getByLabelText('모델'), 'gpt-6.1-sol')
  expect(screen.getByRole('button', { name: '연결' })).toBeEnabled()
  expect(changed).toHaveBeenLastCalledWith({ provider: 'CODEX', state: 'AUTHENTICATED', model: 'gpt-6-luna' })
  expect(api).toHaveBeenCalledTimes(1)
  await userEvent.click(screen.getByRole('button', { name: '연결' }))
  expect(await screen.findByRole('button', { name: '연결됨' })).toBeDisabled()
  expect(changed).toHaveBeenLastCalledWith({ provider: 'CODEX', state: 'AUTHENTICATED', model: 'gpt-6.1-sol' })
  expect(api).toHaveBeenCalledTimes(2)
})

it('확인 전·진행 중·로그인 확인 결과를 눈에 띄는 상태로 전달한다', async () => {
  let finish!: (value: unknown) => void
  api.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const changed = vi.fn()
  render(<ProviderConnectionPanel onClose={vi.fn()} onConnectionChange={changed} />)
  expect(screen.queryByText(/Codex · 아직 확인하지 않음/)).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '연결' }))
  expect(changed).toHaveBeenLastCalledWith({ provider: 'CODEX', state: 'CHECKING', model: 'gpt-6.1-sol' })
  finish({ provider: 'CODEX', state: 'AUTHENTICATED', checkedAt: 'time', remoteConnection: 'NOT_CHECKED' })
  expect(await screen.findByRole('button', { name: '연결됨' })).toBeDisabled()
  expect(screen.queryByText(/Codex · 로그인 확인/)).not.toBeInTheDocument()
  await waitFor(() => expect(changed).toHaveBeenLastCalledWith({ provider: 'CODEX', state: 'AUTHENTICATED', model: 'gpt-6.1-sol' }))
  await userEvent.click(screen.getByRole('button', { name: /Claude/ }))
  expect(changed).toHaveBeenLastCalledWith({ provider: 'CLAUDE', state: 'UNCHECKED', model: 'sonnet' })
})

it('통신 실패를 로그인 성공과 구별한다', async () => {
  api.mockRejectedValue(new Error('가상 연결 오류'))
  const changed = vi.fn()
  render(<ProviderConnectionPanel onClose={vi.fn()} onConnectionChange={changed} />)
  await userEvent.click(screen.getByRole('button', { name: '연결' }))
  expect(await screen.findByText(/Codex · 확인 실패/)).toBeInTheDocument()
  expect(changed).toHaveBeenLastCalledWith({ provider: 'CODEX', state: 'CHECK_FAILED', model: 'gpt-6.1-sol' })
})

it('선택만 기억하고 실행하지 않으며 새 초안에 기본 제공자를 적용한다', async () => {
  const view = render(<ProviderConnectionPanel onClose={vi.fn()} />)
  expect(screen.queryByText(/전용 로그인을 사용합니다/)).not.toBeInTheDocument()
  expect(screen.queryByLabelText('Codex 실행 명령 또는 경로')).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: /Claude/ }))
  expect(screen.getByRole('button', { name: /Claude/ })).toHaveAttribute('aria-pressed', 'true')
  expect(api).not.toHaveBeenCalled()
  expect(preferredProvider()).toBe('CLAUDE')
  view.unmount()
  render(<GenerationPanel base="/knowledge/test" disabled={false} onBusy={vi.fn()} onQueued={vi.fn()} />)
  expect(screen.getByLabelText('AI 제공자')).toHaveValue('CLAUDE')
  expect(api).not.toHaveBeenCalled()
})

it('인증 확인을 원격 모델 성공으로 표시하지 않고 고급 경로를 숨긴다', async () => {
  api.mockResolvedValue({ provider: 'CODEX', state: 'AUTHENTICATED', checkedAt: 'synthetic-time', remoteConnection: 'NOT_CHECKED' })
  render(<ProviderConnectionPanel onClose={vi.fn()} />)
  await userEvent.click(screen.getByRole('button', { name: '연결' }))
  expect(await screen.findByRole('button', { name: '연결됨' })).toBeDisabled()
  expect(screen.queryByText(/Codex · 로그인 확인/)).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '연결됨' })).toBeDisabled()
  expect(api).toHaveBeenCalledTimes(1)
  expect(api).toHaveBeenCalledWith('/ai/providers/CODEX/connection', expect.objectContaining({ method: 'POST' }))
})

it('선택 변경 시 늦게 도착한 이전 제공자 결과를 무시한다', async () => {
  let finish!: (value: unknown) => void
  api.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  render(<ProviderConnectionPanel onClose={vi.fn()} />)
  await userEvent.click(screen.getByRole('button', { name: '연결' }))
  await userEvent.click(screen.getByRole('button', { name: /Claude/ }))
  finish({ provider: 'CODEX', state: 'AUTHENTICATED', checkedAt: 'time', remoteConnection: 'NOT_CHECKED' })
  await waitFor(() => expect(screen.getByRole('button', { name: '연결' })).toBeEnabled())
  expect(screen.queryByText(/Codex · 로그인 확인/)).not.toBeInTheDocument()
})

it('도구 없음과 로그인 필요를 구별하며 별도 동의 전에 로그인하지 않는다', async () => {
  api.mockResolvedValueOnce({ provider: 'CODEX', state: 'TOOL_UNAVAILABLE', checkedAt: 'time', remoteConnection: 'NOT_CHECKED' })
    .mockResolvedValueOnce({ provider: 'CODEX', state: 'LOGIN_REQUIRED', checkedAt: 'time', remoteConnection: 'NOT_CHECKED' })
  render(<ProviderConnectionPanel onClose={vi.fn()} />)
  await userEvent.click(screen.getByRole('button', { name: '연결' }))
  expect(await screen.findByText(/AI 실행 도구를 찾지 못했습니다/)).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '연결' }))
  expect(await screen.findByText(/로그인이 필요합니다/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '브라우저에서 로그인' })).toBeDisabled()
})

it('별도 동의 후 로그인하고 상태 조회만으로 완료를 관측한다', async () => {
  api.mockResolvedValueOnce({ provider: 'CODEX', state: 'LOGIN_REQUIRED', checkedAt: 'time', remoteConnection: 'NOT_CHECKED' })
    .mockResolvedValueOnce({ id: 'login-one', provider: 'CODEX', state: 'PENDING' })
    .mockResolvedValueOnce({ id: 'login-one', provider: 'CODEX', state: 'AUTHENTICATED' })
  render(<ProviderConnectionPanel onClose={vi.fn()} />)
  await userEvent.click(screen.getByRole('button', { name: '연결' }))
  await userEvent.click(await screen.findByRole('checkbox'))
  await userEvent.click(screen.getByRole('button', { name: '브라우저에서 로그인' }))
  expect(screen.getByRole('button', { name: /Claude/ })).toBeDisabled()
  expect(await screen.findByRole('button', { name: '연결됨' }, { timeout: 2500 })).toBeDisabled()
  expect(api).toHaveBeenCalledWith('/ai/logins', expect.objectContaining({ method: 'POST', body: JSON.stringify({ provider: 'CODEX', acknowledged: true }) }))
  expect(api).toHaveBeenCalledWith('/ai/logins/login-one', expect.objectContaining({ signal: expect.any(AbortSignal) }))
})

it('로그인 대기는 별도 중단 요청으로 취소한다', async () => {
  api.mockResolvedValueOnce({ provider: 'CODEX', state: 'LOGIN_REQUIRED', checkedAt: 'time', remoteConnection: 'NOT_CHECKED' })
    .mockResolvedValueOnce({ id: 'login-one', provider: 'CODEX', state: 'PENDING' })
    .mockResolvedValueOnce({ id: 'login-one', provider: 'CODEX', state: 'CANCELLED' })
  render(<ProviderConnectionPanel onClose={vi.fn()} />)
  await userEvent.click(screen.getByRole('button', { name: '연결' }))
  await userEvent.click(await screen.findByRole('checkbox'))
  await userEvent.click(screen.getByRole('button', { name: '브라우저에서 로그인' }))
  await userEvent.click(await screen.findByRole('button', { name: '로그인 중단' }))
  expect(api).toHaveBeenCalledWith('/ai/logins/login-one', expect.objectContaining({ method: 'DELETE' }))
  expect(await screen.findByText(/로그인 대기를 중단했습니다/)).toBeInTheDocument()
})
