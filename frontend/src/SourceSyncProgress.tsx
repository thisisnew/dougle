import { useEffect, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'

type Progress = { stage: string; file: string; completed: number; total: number; cancellable: boolean }
const stages: Record<string, string> = { READING: '파일 읽는 중', READING_BYTES: '파일 복사 중', EXTRACTING: '내용 추출 중', PAGES: '페이지 읽는 중', SPLITTING: '파일 분할 중', PUBLISHING: '검색 자료 저장 중', SYNCING: '동기화 준비 중' }

export default function SourceSyncProgress({ workspaceId, sourceId, onCancel }: {
  workspaceId: string; sourceId: string; onCancel: () => void
}) {
  const [progress, setProgress] = useState<Progress | null>(null)
  const [cancelling, setCancelling] = useState(false)
  const [error, setError] = useState('')
  const base = `/workspaces/${encodeURIComponent(workspaceId)}/sources/${encodeURIComponent(sourceId)}`
  useEffect(() => {
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    setProgress(null); setCancelling(false); setError('')
    async function refresh() {
      try {
        const value = await request<Progress>(`${base}/progress`, { signal: controller.signal })
        if (!controller.signal.aborted) { setProgress(value); setError('') }
      } catch { if (!controller.signal.aborted) setError('진행 상태 확인 중…') }
      finally { if (!controller.signal.aborted) timer = setTimeout(() => void refresh(), 700) }
    }
    void refresh()
    return () => { controller.abort(); clearTimeout(timer) }
  }, [base])
  async function cancel() {
    if (cancelling) return
    setCancelling(true)
    try {
      await request(`${base}/cancel`, { method: 'POST' })
      onCancel()
    } catch { setCancelling(false); setError('중단하지 못했습니다. 다시 시도해 주세요.') }
  }
  const label = cancelling ? '중단 중…' : error || (progress?.stage === 'RETRYING' ? '연결 재시도 중' : progress?.stage === 'EXTRACTING_CHARS' ? '내용 추출 중' : stages[progress?.stage ?? '']) || '동기화 중…'
  return <div className="source-sync-detail" role="status">
    <div><span>{label}</span><span className="source-sync-file" title={progress?.file}>{progress?.file}</span>
      {progress && progress.total > 0 && <span>{progress.stage === 'READING_BYTES' ? `${(progress.completed / 1024 / 1024).toFixed(1)} / ${(progress.total / 1024 / 1024).toFixed(1)} MiB` : `${progress.completed.toLocaleString()} / ${progress.total.toLocaleString()}`}</span>}
      {progress?.stage === 'PAGES' && progress.total === 0 && <span>{progress.completed}페이지</span>}
      {progress?.cancellable && <Button size="sm" variant="ghost" disabled={cancelling} onClick={() => void cancel()}>중단</Button>}
    </div>
    <progress aria-label="동기화 진행률" max={progress?.total || undefined} value={progress && progress.total > 0 ? progress.completed : undefined} />
  </div>
}
