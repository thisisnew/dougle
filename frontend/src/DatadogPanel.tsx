import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'

const sites = ['US1', 'US3', 'US5', 'EU1', 'AP1', 'AP2', 'UK1'] as const
type Site = typeof sites[number]
type Status = { configured: boolean; site: Site | null; revision: string | null }
export type DatadogPanelProps = {
  workspaceId: string | null; disabled?: boolean; onBusyChange?: (busy: boolean) => void; onChanged?: () => void
}

export default function DatadogPanel(props: DatadogPanelProps) {
  return props.workspaceId ? <WorkspaceDatadogPanel key={props.workspaceId} {...props} workspaceId={props.workspaceId} />
    : <section aria-label="Datadog"><h2>Datadog</h2><p>Workspace를 선택한 뒤 Datadog 연결을 설정하세요.</p></section>
}

function WorkspaceDatadogPanel({ workspaceId, disabled = false, onBusyChange, onChanged }:
  DatadogPanelProps & { workspaceId: string }) {
  const [status, setStatus] = useState<Status | null>(null)
  const [admin, setAdmin] = useState(false)
  const [site, setSite] = useState<Site | ''>('')
  const [apiKey, setApiKey] = useState('')
  const [applicationKey, setApplicationKey] = useState('')
  const [busy, setBusy] = useState('상태 확인 중…')
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const locked = useRef(false)
  const lifetime = useRef<AbortController | null>(null)
  const base = `/plugins/datadog/${encodeURIComponent(workspaceId)}`

  useEffect(() => {
    const controller = new AbortController()
    lifetime.current = controller
    request<Status>(base, { signal: controller.signal }).then(value => {
      if (!controller.signal.aborted) setStatus(value)
    }).catch(() => { if (!controller.signal.aborted) setError('Datadog 설정 상태를 불러오지 못했습니다.') })
      .finally(() => { if (!controller.signal.aborted) setBusy('') })
    return () => { controller.abort() }
  }, [base])
  useEffect(() => { onBusyChange?.(!!busy); return () => onBusyChange?.(false) }, [busy, onBusyChange])

  async function configure(action: 'save' | 'disconnect') {
    if (disabled || busy || locked.current || !lifetime.current) return
    if (action === 'save' && (!site || !validKey(apiKey) || !validKey(applicationKey))) return
    locked.current = true; setBusy(action === 'save' ? '관리자 연결 저장 중…' : '연결 해제 중…'); setError(''); setMessage('')
    const signal = lifetime.current.signal
    const body = action === 'save' ? JSON.stringify({ site, apiKey, applicationKey }) : undefined
    setApiKey(''); setApplicationKey('')
    try {
      const saved = await request<Status | undefined>(base, { method: action === 'save' ? 'PUT' : 'DELETE', body, signal })
      if (signal.aborted) return
      if (action === 'save') {
        if (!saved?.configured || !saved.revision || !saved.site) throw new Error('Invalid configuration response')
        setStatus(saved); setAdmin(false)
        setMessage('연결 정보를 저장했습니다. Datadog 접근 권한은 아직 확인하지 않았습니다.')
      } else {
        setStatus({ configured: false, site: null, revision: null }); setAdmin(false); setSite('')
        setMessage('저장된 Datadog 연결 정보를 삭제했습니다.')
      }
      onChanged?.()
    } catch {
      if (!signal.aborted) setError('Datadog 연결 설정을 완료하지 못했습니다. 저장 상태를 확인한 뒤 다시 시도하세요.')
    } finally { locked.current = false; if (!signal.aborted) setBusy('') }
  }

  const controlsDisabled = disabled || !!busy
  return <section className="source-form" aria-label="Datadog">
    <h2>Datadog</h2>
    <p>브라우저 간편 연결(OAuth)은 아직 제공되지 않습니다. 관리자 연결로 API 키와 읽기 권한이 있는 Application 키를 설정할 수 있습니다.</p>
    <p>{status?.configured ? `연결 정보 저장됨 · ${status.site} · 접근 권한 미확인` : '연결 정보 없음'}</p>
    <Button variant="outline" disabled={controlsDisabled} aria-expanded={admin} onClick={() => {
      setAdmin(!admin); setApiKey(''); setApplicationKey(''); setSite(status?.site ?? '')
    }}>관리자 연결</Button>
    {admin && <form onSubmit={event => { event.preventDefault(); void configure('save') }}>
      <fieldset disabled={controlsDisabled}>
        <legend>수동 API 연결 설정</legend>
        <p>Datadog 관리자가 발급한 키를 입력하세요. 로그 읽기 권한(logs_read_data)이 필요합니다. 저장만으로 로그를 조회하지 않습니다.</p>
        <label>Datadog 사이트<select value={site} onChange={event => setSite(event.target.value as Site | '')}>
          <option value="">사이트 선택</option>{sites.map(value => <option key={value} value={value}>{value}</option>)}
        </select></label>
        <label>Datadog API 키<input type="password" autoComplete="new-password" spellCheck={false} maxLength={256} value={apiKey} onChange={event => setApiKey(event.target.value)} /></label>
        <label>Datadog Application 키<input type="password" autoComplete="new-password" spellCheck={false} maxLength={256} value={applicationKey} onChange={event => setApplicationKey(event.target.value)} /></label>
        <Button type="submit" disabled={!site || !validKey(apiKey) || !validKey(applicationKey)}>연결 정보 저장</Button>
        {status?.configured && <Button type="button" variant="destructive" onClick={() => void configure('disconnect')}>Datadog 연결 해제</Button>}
      </fieldset>
    </form>}
    {busy && <p role="status">{busy}</p>}
    {error && <p role="alert">{error}</p>}
    {message && <p role="status">{message}</p>}
  </section>
}

function validKey(value: string) { return /^[A-Za-z0-9_-]{16,256}$/.test(value) }
