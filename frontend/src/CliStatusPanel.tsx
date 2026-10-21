import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'

type Status = { status: string; version: string | null; requestedModel: string }

export default function CliStatusPanel({ provider, disabled }: { provider: string; disabled: boolean }) {
  const [status, setStatus] = useState<Status | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const pending = useRef<AbortController | null>(null)
  useEffect(() => {
    setStatus(null); setError(''); setBusy(false)
    return () => pending.current?.abort()
  }, [provider])

  async function probe() {
    if (disabled || busy) return
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setStatus(null); setError('')
    try {
      const result = await request<Status>(`/ai/providers/${encodeURIComponent(provider)}/probe`, {
        method: 'POST', signal: controller.signal,
      })
      if (!controller.signal.aborted) setStatus(result)
    } catch (reason) {
      if (!controller.signal.aborted) setError((reason as Error).message)
    } finally {
      if (!controller.signal.aborted) setBusy(false)
    }
  }

  return <div>
    <Button variant="outline" disabled={disabled || busy} onClick={() => void probe()}>CLI 설치 확인</Button>
    {busy && <p role="status">로컬 CLI 버전 확인 중…</p>}
    {status && <p role="status">{status.status === 'AVAILABLE' ? `CLI 실행 확인 · 버전 ${status.version}`
      : status.status === 'UNRECOGNIZED' ? '버전 응답을 인식하지 못했습니다.' : 'CLI 실행을 확인하지 못했습니다. 설치·실행 경로를 확인하세요.'}
      {' '}서버 기본 모델: {status.requestedModel || 'CLI 기본값'}. 로그인·원격 연결·모델 이용 가능 여부는 미확인입니다.</p>}
    {error && <p role="alert">{error}</p>}
  </div>
}
