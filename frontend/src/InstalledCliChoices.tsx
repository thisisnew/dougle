import { useEffect, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'
import type { Provider } from './providerPreference'

type Candidate = { name: string; path: string }
type Configuration = { saved: { codexPath: string; claudePath: string; revision: number }; restartRequired: boolean }

export default function InstalledCliChoices({ provider, unavailable, disabled, onPendingChange, onRestartRequired }: {
  provider: Provider; unavailable: boolean; disabled: boolean; onPendingChange: (pending: boolean) => void; onRestartRequired?: () => void
}) {
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
    return () => controller.abort()
  }, [provider, refresh])

  async function select(candidate: Candidate) {
    if (busy || disabled) return
    setBusy(true); setError(''); onPendingChange(true)
    try {
      const configuration = await request<Configuration>('/ai/launch-settings')
      const result = await request<Configuration>('/ai/launch-settings', { method: 'PUT', body: JSON.stringify({
        codexPath: configuration.saved.codexPath, claudePath: configuration.saved.claudePath,
        [provider === 'CODEX' ? 'codexPath' : 'claudePath']: candidate.path,
        expectedRevision: configuration.saved.revision, acknowledged: true,
      }) })
      setMessage(result.restartRequired ? '선택했습니다. 앱을 다시 열면 적용됩니다.' : '사용 중인 도구입니다.')
      if (result.restartRequired) onRestartRequired?.()
    } catch (reason) { setError((reason as Error).message) }
    finally { setBusy(false); onPendingChange(false) }
  }

  if (!unavailable && candidates.length < 2) return null
  return <section aria-label="설치된 AI 도구">
    {loading && <p role="status">설치된 도구를 찾는 중…</p>}
    {candidates.length > 0 && <>
      <p>설치된 도구를 찾았습니다. 사용할 도구를 선택하세요.</p>
      {candidates.map(candidate => <Button key={candidate.path} variant="outline" disabled={busy || disabled}
        onClick={() => void select(candidate)}>{candidate.name} 사용</Button>)}
    </>}
    {unavailable && !loading && candidates.length === 0 && !error && <p>AI 실행 도구를 찾지 못했습니다. 터미널 전용 별칭·함수는 자동 연결되지 않을 수 있습니다.</p>}
    <Button variant="ghost" disabled={busy || disabled || loading} onClick={() => setRefresh(value => value + 1)}>다시 찾기</Button>
    {message && <p role="status">{message}</p>}
    {error && <p role="alert">{error}</p>}
  </section>
}
