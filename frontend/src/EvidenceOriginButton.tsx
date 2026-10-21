import { useEffect, useRef, useState } from 'react'
import { request, type EvidenceLocation } from './api'
import { Button } from './components/ui/button'

export default function EvidenceOriginButton({ path, disabled, onOpen, onBusy }: {
  path: string; disabled: boolean; onOpen: (location: EvidenceLocation) => void; onBusy: (busy: boolean) => void
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const pending = useRef<AbortController | null>(null)
  useEffect(() => () => pending.current?.abort(), [path])

  async function open() {
    if (disabled || busy) return
    const controller = new AbortController()
    pending.current = controller
    setBusy(true)
    onBusy(true)
    setError('')
    try {
      const location = await request<EvidenceLocation>(path, { signal: controller.signal })
      if (!controller.signal.aborted) onOpen(location)
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) { setBusy(false); onBusy(false) } }
  }

  return <div>
    <Button variant="outline" size="sm" disabled={disabled || busy} onClick={() => void open()}>
      {busy ? '현재 원본 확인 중…' : '현재 원본에서 보기'}
    </Button>
    {error && <p role="alert">{error}</p>}
  </div>
}
