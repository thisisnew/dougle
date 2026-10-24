import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'
import { preferredProvider, rememberProvider, type Provider } from './providerPreference'
import { connectionLabels, type ConnectionIndicator } from './connectionIndicator'
import { loadModel, saveModel, requestedModel, modelPresets, type ModelChoice } from './modelPreference'

const AdvancedSettings = lazy(() => import('./CliLaunchPanel'))
type Status = { provider: Provider; state: string; checkedAt: string; remoteConnection: string }
type Login = { id: string; provider: Provider; state: 'PENDING' | 'AUTHENTICATED' | 'FAILED' | 'CANCELLED' | 'TIMED_OUT' }
const messages: Record<string, string> = {
  AUTHENTICATED: '로그인 상태를 확인했습니다. 근거를 선택하고 질문할 수 있습니다.',
  LOGIN_REQUIRED: '로그인이 필요합니다. 아래 버튼을 누르면 기본 브라우저에서 로그인합니다.',
  TOOL_UNAVAILABLE: '이 앱에서 AI 실행 도구를 찾지 못했습니다. 실행 도구가 포함된 배포가 아직 필요합니다.',
  UNSUPPORTED: '현재 실행 도구는 안전한 인증 확인을 지원하지 않습니다. 연결 완료로 표시하지 않습니다.',
  CHECK_FAILED: '연결 상태를 확인하지 못했습니다. 잠시 후 다시 확인해 주세요.',
}

export default function ProviderConnectionPanel({ onClose, onConnectionChange, checkOnOpen = false, initialConnection }: { onClose: () => void; onConnectionChange?: (value: ConnectionIndicator) => void; checkOnOpen?: boolean; initialConnection?: ConnectionIndicator | null }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const pending = useRef<AbortController | null>(null)
  const [provider, setProvider] = useState<Provider>(preferredProvider)
  const [choice, setChoice] = useState<ModelChoice>(() => loadModel(provider))
  const [status, setStatus] = useState<Status | null>(() => initialConnection?.provider === provider && initialConnection.state === 'AUTHENTICATED'
    ? { provider, state: 'AUTHENTICATED', checkedAt: '', remoteConnection: 'NOT_CHECKED' } : null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [advanced, setAdvanced] = useState(false)
  const [login, setLogin] = useState<Login | null>(null)
  const [acknowledged, setAcknowledged] = useState(false)
  const [loginBusy, setLoginBusy] = useState(false)
  const loggingIn = login?.state === 'PENDING'
  const indicatorState = error ? 'CHECK_FAILED' : busy ? 'CHECKING' : status?.state ?? 'UNCHECKED'
  const preset = choice.mode === 'custom' && modelPresets[provider].some(item => item.id === choice.model)
  useEffect(() => {
    onConnectionChange?.({ provider, state: indicatorState, model: requestedModel(choice) })
  }, [provider, indicatorState, choice, onConnectionChange])
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
        if (value.state === 'AUTHENTICATED') setStatus({ provider, state: 'AUTHENTICATED', checkedAt: new Date().toISOString(), remoteConnection: 'NOT_CHECKED' })
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
        if (value.state === 'AUTHENTICATED') setStatus({ provider, state: 'AUTHENTICATED', checkedAt: new Date().toISOString(), remoteConnection: 'NOT_CHECKED' })
      }
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) { setBusy(false); setLoginBusy(false) } }
  }

  function choose(next: Provider) {
    if (next === provider) return
    pending.current?.abort()
    setProvider(next); setChoice(loadModel(next)); setStatus(null); setBusy(false); setLogin(null); setAcknowledged(false)
    setError(rememberProvider(next) ? '' : '이 환경에서는 선택을 기억할 수 없습니다. 현재 화면에서는 사용할 수 있습니다.')
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
        setStatus(value)
      }
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }

  return <dialog ref={dialog} className="proposal-merge provider-connection" aria-label="AI 연결" onCancel={event => { event.preventDefault(); if (!loggingIn && !busy) onClose() }}>
    <div className="provider-connection-header"><div><h2>AI 연결</h2><p>함께 사용할 AI를 골라 주세요.</p></div><Button variant="ghost" disabled={loggingIn || busy} onClick={onClose}>닫기</Button></div>
    <div className="provider-cards" role="group" aria-label="사용할 AI">
      {(['CODEX', 'CLAUDE'] as const).map(value => <button key={value} className="provider-card" aria-pressed={provider === value} disabled={loggingIn || loginBusy} onClick={() => choose(value)}>
        <span className="provider-monogram">{value === 'CODEX' ? 'C' : '✳'}</span><strong>{value === 'CODEX' ? 'Codex' : 'Claude'}</strong><small>{value === 'CODEX' ? 'OpenAI · 앱에 포함' : 'Anthropic · 별도 도구 필요'}</small>
      </button>)}
    </div>
    <label className="field-label">모델<select value={preset ? choice.model : choice.mode} disabled={busy || loggingIn} onChange={event => {
      const value = event.target.value
      setChoice(modelPresets[provider].some(item => item.id === value) ? { mode: 'custom', model: value } : { mode: value as ModelChoice['mode'], model: '' }); setStatus(null); setError('')
    }}><option value="server">앱 기본 모델 — 권장</option>{modelPresets[provider].map(item => <option key={item.id} value={item.id}>{item.label}</option>)}<option value="cli">제공자가 선택하는 기본 모델</option><option value="custom">모델 ID 직접 지정</option></select></label>
    {choice.mode === 'custom' && !preset && <label className="field-label">모델 ID<input value={choice.model} maxLength={128} disabled={busy || loggingIn} placeholder="계정에서 이용 가능한 모델 ID" onChange={event => { setChoice({ ...choice, model: event.target.value }); setStatus(null); setError('') }} /></label>}
    <p className="model-note">모델 이용 가능 여부는 계정에 따라 다릅니다.</p>
    <Button className="connect-button" disabled={busy || advanced || loggingIn || status?.state === 'AUTHENTICATED'} onClick={() => void check()}>{busy ? '확인 중…' : status?.state === 'AUTHENTICATED' ? '연결됨' : '연결'}</Button>
    <p className="connection-status" data-state={indicatorState} role="status" aria-live="polite">{provider === 'CODEX' ? 'Codex' : 'Claude'} · {connectionLabels[indicatorState]}</p>
    {status && status.state !== 'AUTHENTICATED' && <p>{messages[status.state]}</p>}
    <p className="connection-note">로그인 확인만 진행합니다. 자료 전송은 질문마다 동의가 필요합니다.</p>
    {error && <p role="alert">{error}</p>}
    {status?.state === 'LOGIN_REQUIRED' && !loggingIn && <div>
      <label className="check-option"><input type="checkbox" checked={acknowledged} disabled={busy} onChange={event => setAcknowledged(event.target.checked)} /><span>브라우저 로그인과 인증 정보 저장에 동의합니다.</span></label>
      <Button disabled={busy || !acknowledged} onClick={() => void loginAction(false)}>브라우저에서 로그인</Button>
    </div>}
    {loggingIn && <div role="status"><p>브라우저에서 로그인을 완료해 주세요. 최대 3분 동안 기다립니다. 중단은 이미 저장된 로그인을 취소하거나 로그아웃하지 않습니다.</p><Button disabled={busy} onClick={() => void loginAction(true)}>로그인 중단</Button></div>}
    {login && ['FAILED', 'CANCELLED', 'TIMED_OUT'].includes(login.state) && <p role="status">{login.state === 'TIMED_OUT' ? '로그인 대기 시간이 지났습니다.' : login.state === 'CANCELLED' ? '로그인 대기를 중단했습니다.' : '로그인 완료를 확인하지 못했습니다.'} 연결 상태를 다시 확인해 주세요.</p>}
    <details><summary>연결 도움말</summary>
      <p>원격 연결·모델 이용 한도는 확인하지 않았습니다. 실제 요청은 별도 전송 승인 후 실행합니다.</p>
      <p>목록은 공식 문서 기준의 선택 예시이며 계정 조회 결과가 아닙니다. Claude 이름은 제공자가 버전을 선택하는 별칭입니다.</p>
      <p>Codex는 앱 전용 로그인을 사용합니다. Claude는 호환되는 실행 도구가 별도로 필요합니다. 기존 요청의 모델과 승인은 변경하지 않습니다.</p>
      <Button variant="outline" disabled={busy || loggingIn} onClick={() => setAdvanced(true)}>기존 실행 경로 설정</Button>
    </details>
    {advanced && <Suspense fallback={<p>고급 설정을 여는 중…</p>}><AdvancedSettings onClose={() => { setAdvanced(false); setStatus(null) }} /></Suspense>}
  </dialog>
}
