import { request } from './api'
import type { Source } from './SourcePanel'

type SyncJob = { id: string; state: 'RUNNING' | 'COMPLETED' | 'FAILED'; source: Source | null; error: string | null }

export async function syncSource(path: string, signal: AbortSignal): Promise<Source> {
  let job = await request<SyncJob>(`${path}/sync-jobs`, { method: 'POST', signal })
  while (job.state === 'RUNNING') {
    await new Promise<void>((resolve, reject) => {
      const stop = () => { clearTimeout(timer); reject(new DOMException('Aborted', 'AbortError')) }
      const timer = setTimeout(() => { signal.removeEventListener('abort', stop); resolve() }, 500)
      if (signal.aborted) stop()
      else signal.addEventListener('abort', stop, { once: true })
    })
    job = await request<SyncJob>(`${path}/sync-jobs/${encodeURIComponent(job.id)}`, { signal })
  }
  if (job.state !== 'COMPLETED' || !job.source) throw new Error(job.error || '자료 동기화를 완료하지 못했습니다.')
  return job.source
}
