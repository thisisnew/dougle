import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'
import { preferredProvider, rememberProvider, type Provider } from './providerPreference'

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

export default function ProviderConnectionPanel({ onClose }: { onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const pending = useRef<AbortController | null>(null)
  const [provider, setProvider] = useState<Provider>(preferredProvider)
  const [status, setStatus] = useState<Status | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [advanced, setAdvanced] = useState(false)
  const [login, setLogin] = useState<Login | null>(null)
  const [acknowledged, setAcknowledged] = useState(false)
  const [loginBusy, setLoginBusy] = useState(false)
  const loggingIn = login?.state === 'PENDING'
  useEffect(() => {
    const element = dialog.current!
    element.showModal()
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
    pending.current?.abort()
    setProvider(next); setStatus(null); setBusy(false); setLogin(null); setAcknowledged(false)
    setError(rememberProvider(next) ? '' : '이 환경에서는 선택을 기억할 수 없습니다. 현재 화면에서는 사용할 수 있습니다.')
  }

  async function check() {
    if (busy) return
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
    <div className="provider-connection-header"><h2>어떤 AI를 사용할까요?</h2><Button variant="outline" disabled={loggingIn || busy} onClick={onClose}>닫기</Button></div>
    <p>한 번 고르면 다음 질문과 지식 초안에서 기본으로 사용합니다. 이미 작성 중인 요청이나 승인은 바꾸지 않습니다.</p>
    <label>사용할 AI<select value={provider} disabled={loggingIn || loginBusy} onChange={event => choose(event.target.value as Provider)}><option value="CODEX">Codex</option><option value="CLAUDE">Claude</option></select></label>
    <Button disabled={busy || advanced || loggingIn} onClick={() => void check()}>{busy ? '확인 중…' : '연결 상태 확인'}</Button>
    <p>확인을 누르면 선택한 도구의 인증 상태만 조회합니다. 자료를 보내거나 AI 답변을 생성하지 않습니다.</p>
    {provider === 'CODEX' && <p>새 Mac 앱은 답변 스트리밍을 위한 전용 로그인을 사용합니다. 처음 한 번 이 앱에서 로그인하세요. 기존 Codex 로그인은 변경하지 않습니다.</p>}
    {status && <div role="status"><p>{messages[status.state]}</p><small>확인 시각: {status.checkedAt}</small><p>원격 연결·모델 이용 한도는 확인하지 않았습니다. 실제 요청은 별도 전송 승인 후 실행합니다.</p></div>}
    {error && <p role="alert">{error}</p>}
    {status?.state === 'LOGIN_REQUIRED' && !loggingIn && <div>
      <label><input type="checkbox" checked={acknowledged} disabled={busy} onChange={event => setAcknowledged(event.target.checked)} />기본 브라우저 로그인과 제공자 도구의 자격 증명 저장에 동의합니다. 자료는 전송하지 않습니다.</label>
      <Button disabled={busy || !acknowledged} onClick={() => void loginAction(false)}>브라우저에서 로그인</Button>
    </div>}
    {loggingIn && <div role="status"><p>브라우저에서 로그인을 완료해 주세요. 최대 3분 동안 기다립니다. 중단은 이미 저장된 로그인을 취소하거나 로그아웃하지 않습니다.</p><Button disabled={busy} onClick={() => void loginAction(true)}>로그인 중단</Button></div>}
    {login && ['FAILED', 'CANCELLED', 'TIMED_OUT'].includes(login.state) && <p role="status">{login.state === 'TIMED_OUT' ? '로그인 대기 시간이 지났습니다.' : login.state === 'CANCELLED' ? '로그인 대기를 중단했습니다.' : '로그인 완료를 확인하지 못했습니다.'} 연결 상태를 다시 확인해 주세요.</p>}
    <details><summary>고급 설정 / 현재 제한</summary>
      <p>새 빌드는 Codex 실행 도구 동봉을 지원합니다. 이전 0.1.1 설치 파일에는 포함되지 않습니다. Claude 동봉·실제 계정 로그인 확인은 별도입니다. 자료 검색·수동 지식 작성은 AI 없이 사용할 수 있습니다.</p>
      <Button variant="outline" disabled={busy || loggingIn} onClick={() => setAdvanced(true)}>기존 실행 경로 설정</Button>
    </details>
    {advanced && <Suspense fallback={<p>고급 설정을 여는 중…</p>}><AdvancedSettings onClose={() => { setAdvanced(false); setStatus(null) }} /></Suspense>}
  </dialog>
}
