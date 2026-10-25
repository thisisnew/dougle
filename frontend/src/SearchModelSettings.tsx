import { useEffect, useState } from 'react'
import { ChevronRight, Search } from 'lucide-react'
import * as Dialog from '@radix-ui/react-dialog'
import { Button } from './components/ui/button'
import { request } from './api'

type Status = { state: string; downloaded: number; total: number; error: string | null }

export default function SearchModelSettings() {
  const [open, setOpen] = useState(false)
  const [status, setStatus] = useState<Status | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => {
    if (!open) return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    async function poll() {
      try {
        const result = await request<Status>('/search-model', { signal: controller.signal })
        if (!controller.signal.aborted) { setStatus(result); setError('') }
      } catch { if (!controller.signal.aborted) setError('검색 설정을 확인하지 못했습니다.') }
      finally { if (!controller.signal.aborted) timer = setTimeout(() => void poll(), 1000) }
    }
    void poll()
    return () => { controller.abort(); clearTimeout(timer) }
  }, [open])
  const busy = pending || !!status && ['STARTING', 'DOWNLOADING', 'LOADING', 'INDEXING'].includes(status.state)
  async function prepare() {
    if (busy) return
    setPending(true); setError('')
    try { setStatus(await request<Status>('/search-model/prepare', { method: 'POST' })) }
    catch { setError('검색 준비를 시작하지 못했습니다.') }
    finally { setPending(false) }
  }
  return <>
    <button type="button" className="settings-row" onClick={() => setOpen(true)}>
      <span className="settings-row-icon" aria-hidden="true"><Search size={22} /></span><span>검색 엔진</span><ChevronRight size={19} aria-hidden="true" />
    </button>
    <Dialog.Root open={open} onOpenChange={setOpen}><Dialog.Portal><Dialog.Overlay className="dialog-overlay" />
      <Dialog.Content className="dialog-content" aria-describedby="search-model-description"><Dialog.Title>검색 엔진</Dialog.Title>
        <Dialog.Description id="search-model-description">앱에 포함된 검색 엔진을 자동으로 준비합니다.</Dialog.Description>
        {status?.state === 'READY' ? <p role="status">사용 중</p> : busy ? <div className="source-sync-detail" role="status">
          <span>{status?.state === 'INDEXING' ? '자료 검색 준비 중…' : '검색 엔진 준비 중…'}</span>
          <progress aria-label="검색 준비" />
        </div> : null}
        {(error || status?.error) && <p role="alert">{error || status?.error}</p>}
        <div className="dialog-actions"><Button variant="outline" onClick={() => setOpen(false)}>닫기</Button>
          {status?.state !== 'READY' && <Button disabled={busy || !status} onClick={() => void prepare()}>{busy ? '준비 중…' : '다시 시도'}</Button>}
        </div>
      </Dialog.Content></Dialog.Portal></Dialog.Root>
  </>
}
