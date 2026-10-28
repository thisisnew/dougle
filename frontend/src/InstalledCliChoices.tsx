import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'
import type { Provider } from './providerPreference'

type Candidate = { name: string; path: string }
type Configuration = { saved: { codexPath: string; claudePath: string; revision: number }; restartRequired: boolean }
export type SelectionStatus = { provider: Provider; state: string; checkedAt: string; remoteConnection: string; reason?: string }

export default function InstalledCliChoices({ provider, unavailable, disabled, onPendingChange, onConnectionResult }: {
  provider: Provider; unavailable: boolean; disabled: boolean; onPendingChange: (pending: boolean) => void; onConnectionResult?: (status: SelectionStatus) => void
}) {
  const selection = useRef<AbortController | null>(null)
  const [candidates, setCandidates] = useState<Candidate[]>([])
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [refresh, setRefresh] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setCandidates([]); setMessage(''); setError(''); setLoading(true)
    void request<Candidate[]>(`/ai/launch-settings/discovered/${provider}`, { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted && Array.isArray(value)) setCandidates(value) })
      .catch(() => { if (!controller.signal.aborted) setError('설치된 도구 목록을 확인하지 못했습니다.') })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => { controller.abort(); selection.current?.abort() }
  }, [provider, refresh])

  async function select(candidate: Candidate) {
    if (selection.current || busy || disabled) return
    const controller = new AbortController()
    selection.current = controller
    setBusy(true); setError(''); onPendingChange(true)
    try {
      const configuration = await request<Configuration>('/ai/launch-settings', { signal: controller.signal })
      if (controller.signal.aborted) return
      const result = await request<SelectionStatus>(`/ai/launch-settings/connect/${provider}`, { method: 'POST', signal: controller.signal, body: JSON.stringify({
        path: candidate.path, expectedRevision: configuration.saved.revision,
      }) })
      if (controller.signal.aborted) return
      if (result.provider !== provider || result.remoteConnection !== 'NOT_CHECKED' || !['AUTHENTICATED', 'LOGIN_REQUIRED', 'CHECK_FAILED', 'UNSUPPORTED', 'TOOL_UNAVAILABLE'].includes(result.state)) throw new Error('연결 확인 결과가 다릅니다. 다시 시도하세요.')
      setMessage(result.state === 'AUTHENTICATED' ? '로그인 상태를 확인했습니다.' : result.state === 'LOGIN_REQUIRED' ? '선택한 도구로 로그인해 주세요.' : '')
      if (!['AUTHENTICATED', 'LOGIN_REQUIRED'].includes(result.state)) setError('연결을 확인하지 못해 기존 설정을 유지했습니다.')
      onConnectionResult?.(result)
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { selection.current = null; if (!controller.signal.aborted) { setBusy(false); onPendingChange(false) } }
  }

  if (!unavailable && candidates.length < 2) return null
  return <section aria-label="설치된 AI 도구">
    {loading && <p role="status">설치된 도구를 찾는 중…</p>}
    {candidates.length > 0 && <>
      <p>설치된 도구를 찾았습니다. 사용할 도구를 선택하세요.</p>
      <div className="installed-cli-choices">{Array.from(new Map(candidates.map(candidate => [candidate.path, candidate])).values()).map(candidate => <Button key={candidate.path} variant="outline" disabled={busy || disabled}
        onClick={() => void select(candidate)}><strong>{candidate.name} 사용</strong>{' '}<small>{candidate.path.startsWith('dougle-alias-') ? '터미널 계정 설정' : candidate.path.includes('/Cellar/') || candidate.path.includes('/homebrew/') ? 'Homebrew 설치' : candidate.path.replace(/^\/Users\/[^/]+/, '~')}</small></Button>)}</div>
    </>}
    {unavailable && !loading && candidates.length === 0 && !error && <p>AI 실행 도구를 찾지 못했습니다. 터미널 전용 별칭·함수는 자동 연결되지 않을 수 있습니다.</p>}
    <Button variant="ghost" disabled={busy || disabled || loading} onClick={() => setRefresh(value => value + 1)}>다시 찾기</Button>
    {message && <p role="status">{message}</p>}
    {busy && <p role="status">선택한 계정의 로그인 상태를 확인하는 중…</p>}
    {error && <p role="alert">{error}</p>}
  </section>
}
