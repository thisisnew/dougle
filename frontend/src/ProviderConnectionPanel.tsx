import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'
import { preferredProvider, rememberProvider, type Provider } from './providerPreference'
import { connectionLabels, type ConnectionIndicator } from './connectionIndicator'
import { loadModel, saveModel, requestedModel, modelPresets, type ModelChoice } from './modelPreference'
import InstalledCliChoices from './InstalledCliChoices'

type Status = { provider: Provider; state: string; checkedAt: string; remoteConnection: string; reason?: string }
type Login = { id: string; provider: Provider; state: 'PENDING' | 'AUTHENTICATED' | 'FAILED' | 'CANCELLED' | 'TIMED_OUT' }
const messages: Record<string, string> = {
  AUTHENTICATED: '로그인 상태를 확인했습니다. 근거를 선택하고 질문할 수 있습니다.',
  LOGIN_REQUIRED: '로그인이 필요합니다. 아래 버튼을 누르면 기본 브라우저에서 로그인합니다.',
  TOOL_UNAVAILABLE: 'AI 실행 도구를 찾지 못했습니다.',
  UNSUPPORTED: '현재 실행 도구는 안전한 인증 확인을 지원하지 않습니다. 연결 완료로 표시하지 않습니다.',
  CHECK_FAILED: '인증 상태를 확인하지 못했습니다. 회사 계정 설정이나 실행 도구의 인증 응답을 확인해야 합니다.',
}
const failureMessages: Record<string, string> = {
  TIMED_OUT: '인증 확인 시간이 초과됐습니다. 회사 네트워크나 VPN 연결을 확인해 주세요.',
  AUTH_RESPONSE_UNRECOGNIZED: '실행 도구의 인증 응답을 해석하지 못했습니다. 회사용 실행 설정과 도구 버전을 확인해야 합니다.',
  AUTH_STATUS_REJECTED: '실행 도구가 인증 확인에 실패했습니다. 터미널에서 사용하는 계정 설정이 필요한지 확인해야 합니다.',
  LAUNCH_CONFIGURATION_CHANGED: '실행 설정이 변경됐습니다. 사용할 계정을 다시 선택해 주세요.',
}

export default function ProviderConnectionPanel({ onClose, onConnectionChange, checkOnOpen = false, initialConnection }: { onClose: () => void; onConnectionChange?: (value: ConnectionIndicator) => void; checkOnOpen?: boolean; initialConnection?: ConnectionIndicator | null }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const pending = useRef<AbortController | null>(null)
  const [provider, setProvider] = useState<Provider>(() => initialConnection?.state === 'AUTHENTICATED' ? initialConnection.provider : preferredProvider())
  const [choice, setChoice] = useState<ModelChoice>(() => loadModel(provider))
  const [status, setStatus] = useState<Status | null>(() => initialConnection?.provider === provider && initialConnection.state === 'AUTHENTICATED'
    ? { provider, state: 'AUTHENTICATED', checkedAt: '', remoteConnection: 'NOT_CHECKED' } : null)
  const verified = useRef<Partial<Record<Provider, Status>>>(status ? { [provider]: status } : {})
  const verifiedModels = useRef<Partial<Record<Provider, string | null>>>(status ? { [provider]: initialConnection?.model ?? requestedModel(choice) } : {})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [login, setLogin] = useState<Login | null>(null)
  const [acknowledged, setAcknowledged] = useState(false)
  const [loginBusy, setLoginBusy] = useState(false)
  const [choosingTool, setChoosingTool] = useState(false)
  const [discoveryOpen, setDiscoveryOpen] = useState(false)
  const loggingIn = login?.state === 'PENDING'
  const indicatorState = error ? 'CHECK_FAILED' : busy ? 'CHECKING' : status?.state ?? 'UNCHECKED'
  const connected = status?.state === 'AUTHENTICATED' && verifiedModels.current[provider] === requestedModel(choice)
  useEffect(() => {
    if (status?.state === 'AUTHENTICATED') verified.current[provider] = status
    if (indicatorState === 'AUTHENTICATED' && !connected) return
    if (initialConnection?.state === 'AUTHENTICATED' && (indicatorState === 'UNCHECKED' || (provider !== initialConnection.provider && indicatorState !== 'AUTHENTICATED'))) return
    if (indicatorState === 'AUTHENTICATED') rememberProvider(provider)
    onConnectionChange?.({ provider, state: indicatorState, model: requestedModel(choice) })
  }, [provider, indicatorState, choice, connected, onConnectionChange, initialConnection?.provider, initialConnection?.state])
  useEffect(() => {
    const element = dialog.current!
    element.showModal()
    if (checkOnOpen) void check()
    return () => { pending.current?.abort(); element.close() }
  }, [])

  useEffect(() => {
    if (!login || login.state !== 'PENDING') return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    async function poll() {
      try {
        const value = await request<Login>(`/ai/logins/${encodeURIComponent(login!.id)}`, { signal: controller.signal })
        if (controller.signal.aborted) return
        if (value.id !== login!.id || value.provider !== provider) throw new Error('로그인 응답이 다릅니다.')
        setLogin(value)
        if (value.state === 'AUTHENTICATED') { verifiedModels.current[provider] = requestedModel(choice); setStatus({ provider, state: 'AUTHENTICATED', checkedAt: new Date().toISOString(), remoteConnection: 'NOT_CHECKED' }) }
        if (value.state !== 'PENDING') return
      } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
      if (!controller.signal.aborted) timer = setTimeout(() => void poll(), 1500)
    }
    timer = setTimeout(() => void poll(), 500)
    return () => { controller.abort(); clearTimeout(timer) }
  }, [login?.id, login?.state, provider])

  async function loginAction(cancel: boolean) {
    if (busy) return
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setLoginBusy(true); setError('')
    try {
      const value = await request<Login>(cancel ? `/ai/logins/${encodeURIComponent(login!.id)}` : '/ai/logins', {
        method: cancel ? 'DELETE' : 'POST', signal: controller.signal,
        ...(cancel ? {} : { body: JSON.stringify({ provider, acknowledged }) }),
      })
      if (!controller.signal.aborted) {
        if (value.provider !== provider) throw new Error('로그인 제공자가 다릅니다.')
        setLogin(value)
        if (value.state === 'AUTHENTICATED') { verifiedModels.current[provider] = requestedModel(choice); setStatus({ provider, state: 'AUTHENTICATED', checkedAt: new Date().toISOString(), remoteConnection: 'NOT_CHECKED' }) }
      }
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) { setBusy(false); setLoginBusy(false) } }
  }

  function choose(next: Provider) {
    if (next === provider) return
    pending.current?.abort()
    setProvider(next); setChoice(loadModel(next)); setStatus(verified.current[next] ?? null); setBusy(false); setLogin(null); setAcknowledged(false); setDiscoveryOpen(false)
    setError(initialConnection?.state === 'AUTHENTICATED' || rememberProvider(next) ? '' : '이 환경에서는 선택을 기억할 수 없습니다. 현재 화면에서는 사용할 수 있습니다.')
  }

  async function check() {
    if (busy) return
    if (choice.mode === 'custom' && !/^[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,127}$/.test(choice.model)) { setError('유효한 모델 ID를 입력해 주세요.'); return }
    if (!saveModel(provider, choice)) { setError('모델 선택을 저장하지 못했습니다. 다시 시도해 주세요.'); return }
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError(''); setStatus(null)
    try {
      const value = await request<Status>(`/ai/providers/${provider}/connection`, { method: 'POST', signal: controller.signal })
      if (!controller.signal.aborted) {
        if (value.provider !== provider || !messages[value.state] || value.remoteConnection !== 'NOT_CHECKED') throw new Error('확인 결과가 예상과 다릅니다. 다시 확인하세요.')
        if (value.state === 'AUTHENTICATED') verifiedModels.current[provider] = requestedModel(choice)
        setStatus(value)
      }
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }

  return <dialog ref={dialog} className="proposal-merge provider-connection" aria-label="AI 연결" onCancel={event => { event.preventDefault(); if (!loggingIn && !busy && !choosingTool) onClose() }}>
    <div className="provider-connection-header"><div><h2>AI 연결</h2></div><Button variant="ghost" disabled={loggingIn || busy || choosingTool} onClick={onClose}>닫기</Button></div>
    <div className="provider-cards" role="group" aria-label="사용할 AI">
      {(['CODEX', 'CLAUDE'] as const).map(value => <button key={value} className="provider-card" aria-pressed={provider === value} disabled={loggingIn || loginBusy || choosingTool} onClick={() => choose(value)}>
        <span className="provider-monogram">{value === 'CODEX' ? 'C' : '✳'}</span><strong>{value === 'CODEX' ? 'Codex' : 'Claude'}</strong><small>{value === 'CODEX' ? 'OpenAI' : 'Anthropic'}</small>
      </button>)}
    </div>
    <label className="field-label">모델<select value={choice.model} disabled={busy || loggingIn || choosingTool} onChange={event => {
      const value = event.target.value
      if (!modelPresets[provider].some(item => item.id === value)) return
      const next: ModelChoice = { mode: 'custom', model: value }
      setChoice(next); setError('')
    }}>{modelPresets[provider].map(item => <option key={item.id} value={item.id}>{item.label}</option>)}</select></label>
    <Button aria-live="polite" className="connect-button" disabled={busy || loggingIn || connected || choosingTool} onClick={() => void check()}>{busy || choosingTool ? '확인 중…' : connected ? '연결됨' : '연결'}</Button>
    {!['UNCHECKED', 'AUTHENTICATED', 'TOOL_UNAVAILABLE'].includes(indicatorState) && <p className="connection-status" data-state={indicatorState} role="status" aria-live="polite">{provider === 'CODEX' ? 'Codex' : 'Claude'} · {connectionLabels[indicatorState]}</p>}
    {status && !['AUTHENTICATED', 'TOOL_UNAVAILABLE'].includes(status.state) && <p>{failureMessages[status.reason ?? ''] ?? messages[status.state]}</p>}
    {(['TOOL_UNAVAILABLE', 'CHECK_FAILED', 'UNSUPPORTED'].includes(status?.state ?? '') || discoveryOpen) && <InstalledCliChoices key={provider} provider={provider} unavailable={true} disabled={busy || loggingIn} onPendingChange={setChoosingTool}
      onConnectionResult={value => {
        setChoosingTool(false); setError(''); setLogin(null)
        delete verified.current[provider]; delete verifiedModels.current[provider]
        if (value.state === 'AUTHENTICATED') {
          if (!saveModel(provider, choice)) { setError('모델 선택을 저장하지 못했습니다. 다시 확인하세요.'); setStatus(null); return }
          verifiedModels.current[provider] = requestedModel(choice)
        }
        setStatus(value); setDiscoveryOpen(false)
      }} />}
    {error && <p role="alert">{error}</p>}
    {status?.state === 'LOGIN_REQUIRED' && !loggingIn && <div>
      <p>회사에서 사용하는 계정으로 로그인하세요. Google 로그인이나 회사 SSO가 나오면 브라우저 안내를 따라 주세요.</p>
      <label className="check-option"><input type="checkbox" checked={acknowledged} disabled={busy} onChange={event => setAcknowledged(event.target.checked)} /><span>브라우저 로그인과 인증 정보 저장에 동의합니다.</span></label>
      <Button disabled={busy || choosingTool || !acknowledged} onClick={() => void loginAction(false)}>브라우저에서 로그인</Button>
    </div>}
    {loggingIn && <div role="status"><p>브라우저에서 로그인을 완료해 주세요. 최대 3분 동안 기다립니다. 중단은 이미 저장된 로그인을 취소하거나 로그아웃하지 않습니다.</p><Button disabled={busy} onClick={() => void loginAction(true)}>로그인 중단</Button></div>}
    {login && ['FAILED', 'CANCELLED', 'TIMED_OUT'].includes(login.state) && <p role="status">{login.state === 'TIMED_OUT' ? '로그인 대기 시간이 지났습니다.' : login.state === 'CANCELLED' ? '로그인 대기를 중단했습니다.' : '로그인 완료를 확인하지 못했습니다.'} 연결 상태를 다시 확인해 주세요.</p>}
  </dialog>
}
