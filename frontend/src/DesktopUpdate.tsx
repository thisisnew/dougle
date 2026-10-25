import { useEffect, useRef, useState } from 'react'
import { AppWindow, ArrowDownToLine, ChevronRight } from 'lucide-react'
import * as Dialog from '@radix-ui/react-dialog'
import { Button } from './components/ui/button'
import { version as appVersion } from '../../desktop/src-tauri/tauri.conf.json'

type Status = { phase: 'idle' | 'checking' | 'current' | 'available' | 'downloading' | 'installing' | 'error'; version?: string; progress?: number }
type DesktopWindow = Window & { __TAURI_INTERNALS__?: { invoke: <T>(command: string) => Promise<T> } }

export default function DesktopUpdate({ visible = true, onAvailableChange }: { visible?: boolean; onAvailableChange?: (available: boolean) => void }) {
  const desktop = (window as DesktopWindow).__TAURI_INTERNALS__
  const [status, setStatus] = useState<Status>({ phase: 'idle' })
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [open, setOpen] = useState(false)
  const active = useRef(true)
  useEffect(() => { active.current = true; return () => { active.current = false } }, [])
  useEffect(() => { if (!visible) setOpen(false) }, [visible])
  useEffect(() => {
    if (!desktop) return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout>
    async function poll() {
      let delay = 2000
      try {
        const value = await desktop!.invoke<Status>('update_status')
        if (!cancelled) {
          setStatus(value)
          if (value.phase !== 'idle') setError('')
        }
        if (['checking', 'downloading', 'installing'].includes(value.phase)) delay = 1000
      } catch { if (!cancelled) setError('업데이트를 확인하지 못했습니다.') }
      finally { if (!cancelled) timer = setTimeout(() => void poll(), pending ? 1000 : delay) }
    }
    void poll()
    return () => { cancelled = true; clearTimeout(timer) }
  }, [desktop, pending])
  useEffect(() => {
    onAvailableChange?.(!!status.version && status.phase === 'available')
  }, [status.version, status.phase, onAvailableChange])
  if (!desktop) return null
  const busy = pending || ['checking', 'downloading', 'installing'].includes(status.phase)
  const available = !!status.version && ['available', 'downloading', 'installing'].includes(status.phase)
  const label = status.phase === 'downloading' ? `다운로드 중 ${status.progress ?? 0}%`
    : status.phase === 'installing' ? '설치 중…' : status.phase === 'checking' || pending ? '업데이트 확인 중…'
      : status.phase === 'error' || error ? '다시 확인' : available ? '새 버전 설치' : '최신 버전 확인'
  async function update() {
    if (busy) return
    setPending(true); setError('')
    try { await desktop!.invoke('install_update') }
    catch { if (active.current) setError('업데이트를 확인하지 못했습니다.') }
    finally { if (active.current) setPending(false) }
  }
  return <div className="desktop-update" hidden={!visible}>
    <button type="button" className="settings-row" onClick={() => setOpen(true)}>
      <span className="settings-row-icon" aria-hidden="true"><AppWindow size={22} /></span>
      <span>앱 업데이트{available && <i className="settings-update-dot" aria-hidden="true" />}</span><ChevronRight size={19} aria-hidden="true" />
    </button>
    <Dialog.Root open={visible && open} onOpenChange={setOpen}>
      <Dialog.Portal><Dialog.Overlay className="dialog-overlay" /><Dialog.Content className="dialog-content app-update-dialog" aria-describedby={undefined}>
        <Dialog.Title>앱 업데이트</Dialog.Title>
        <dl className="app-version-details"><div><dt>현재 버전</dt><dd>v{appVersion}</dd></div>{available && <div><dt>새 버전</dt><dd>v{status.version}</dd></div>}</dl>
        {status.phase === 'current' && !pending && !error && <p role="status">최신 버전입니다.</p>}
        {(status.phase === 'error' || error) && <p role="alert" className="modal-error">앱 업데이트를 확인하지 못했습니다. 다시 시도해 주세요.</p>}
        <div className="dialog-actions"><Button variant="outline" onClick={() => setOpen(false)}>닫기</Button><Button disabled={busy} onClick={() => void update()}>
          {busy ? <span className="upload-spinner" aria-hidden="true" /> : available ? <ArrowDownToLine size={16} aria-hidden="true" /> : null}<span aria-live="polite">{label}</span>
        </Button></div>
      </Dialog.Content></Dialog.Portal>
    </Dialog.Root>
  </div>
}
