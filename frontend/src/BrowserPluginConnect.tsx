import { useEffect, useRef, useState } from 'react'
import { ExternalLink, LoaderCircle } from 'lucide-react'
import { Button } from './components/ui/button'
import { request } from './api'

type Progress = { id: string; state: string; message: string }
type Props = { provider: string; connected: boolean; ready: boolean; onBusyChange: (busy: boolean) => void; onConnected: () => Promise<void>; onRefresh: () => Promise<void>; onClose: () => void }

export default function BrowserPluginConnect({ provider, connected, ready, onBusyChange, onConnected, onRefresh, onClose }: Props) {
  const [progress, setProgress] = useState<Progress | null>(null)
  const [busy, setBusy] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [error, setError] = useState('')
  const alive = useRef(true)
  const attempt = useRef<string | null>(null)
  const callbacks = useRef({ onConnected, onRefresh, onBusyChange })
  callbacks.current = { onConnected, onRefresh, onBusyChange }
  const base = `/plugins/browser/${provider}`
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
      if (attempt.current) void request(`${base}/${attempt.current}`, { method: 'DELETE' }).catch(() => {})
      callbacks.current.onBusyChange(false)
    }
  }, [base])
  useEffect(() => { onBusyChange(busy) }, [busy, onBusyChange])
  useEffect(() => {
    if (!progress?.id || !busy || cancelling) return
    const id = progress.id
    const abort = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    let failures = 0
    async function poll() {
      try {
        const result = await request<Progress>(`${base}/${id}`, { signal: abort.signal })
        if (abort.signal.aborted || attempt.current !== id) return
        failures = 0
        setProgress(result)
        if (['STARTING', 'WAITING', 'VERIFYING'].includes(result.state)) { timer = setTimeout(poll, 1000); return }
        attempt.current = null
        if (result.state === 'CONNECTED') await callbacks.current.onConnected()
        else setError(result.message)
        if (alive.current) setBusy(false)
      } catch (reason) {
        if (abort.signal.aborted) return
        if (attempt.current !== id) {
          setError(reason instanceof Error ? reason.message : '연결 후 자료를 갱신하지 못했습니다.')
          setBusy(false)
          return
        }
        if (++failures < 3) { timer = setTimeout(poll, 1500); return }
        setError(reason instanceof Error ? reason.message : '연결 상태를 확인하지 못했습니다.')
        void request(`${base}/${id}`, { method: 'DELETE' }).catch(() => {})
        attempt.current = null
        setBusy(false)
      }
    }
    timer = setTimeout(poll, 300)
    return () => { abort.abort(); clearTimeout(timer) }
  }, [base, progress?.id, busy, cancelling])
  async function start() {
    if (busy) return
    setBusy(true); setError(''); setProgress(null)
    try {
      const result = await request<Progress>(base, { method: 'POST' })
      if (!alive.current) { await request(`${base}/${result.id}`, { method: 'DELETE' }); return }
      attempt.current = result.id; setProgress(result)
    } catch (reason) {
      if (alive.current) { setError(reason instanceof Error ? reason.message : '연결을 시작하지 못했습니다.'); setBusy(false) }
    }
  }
  async function cancel() {
    if (!attempt.current || cancelling) return
    setCancelling(true); setError('')
    try {
      await request(`${base}/${attempt.current}`, { method: 'DELETE' })
      attempt.current = null
      setProgress(null); setBusy(false)
      await callbacks.current.onRefresh()
    } catch { setError('연결 취소를 확인하지 못했습니다. 다시 시도해 주세요.') }
    finally { setCancelling(false) }
  }
  async function disconnect() {
    setBusy(true); setError('')
    try {
      await request(`/plugins/${provider}`, { method: 'DELETE' })
      await callbacks.current.onRefresh(); setProgress(null)
    } catch (reason) { setError(reason instanceof Error ? reason.message : '연결을 해제하지 못했습니다.') }
    finally { setBusy(false) }
  }
  return <>
    {busy ? <p className="plugin-message" role="status"><LoaderCircle className="animate-spin" size={17} />{cancelling ? '연결을 취소하는 중…' : progress?.message || '브라우저를 여는 중…'}</p>
      : <p className="plugin-message">{connected ? '연결되었습니다.' : '브라우저에서 로그인하고 연결을 승인해 주세요.'}</p>}
    {error && <p className="plugin-message error" role="alert">{error}</p>}
    <div className="dialog-actions plugin-actions">
      {connected && !busy && <Button variant="ghost" onClick={() => void disconnect()}>연결 해제</Button>}
      {busy ? <Button variant="outline" disabled={!attempt.current || cancelling} onClick={() => void cancel()}>취소</Button>
        : <><Button variant="outline" onClick={onClose}>닫기</Button><Button disabled={!ready} onClick={() => void start()}><ExternalLink size={16} />{connected ? '다시 연결' : '연결'}</Button></>}
    </div>
  </>
}
