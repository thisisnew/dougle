import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'

export type GenerationJob = {
  id: string; sourceCardId: string; provider: string; status: string
  resultCardId: string | null; error: string | null; createdAt: string
}
const labels: Record<string, string> = {
  QUEUED: '대기', RUNNING: '생성 중', CANCELLING: '중단 처리 중', CANCELLED: '취소됨',
  SUCCEEDED: '완료', FAILED: '실패', INTERRUPTED: '앱 재시작으로 중단됨',
}
const active = (job: GenerationJob) => ['QUEUED', 'RUNNING', 'CANCELLING'].includes(job.status)

export default function GenerationJobsPanel({ workspaceId, revision, onOpen, openDisabled = false }: {
  workspaceId: string; revision: number; onOpen: (id: string) => void; openDisabled?: boolean
}) {
  const [jobs, setJobs] = useState<GenerationJob[]>([])
  const [error, setError] = useState('')
  const [refresh, setRefresh] = useState(0)
  const [cancelling, setCancelling] = useState('')
  const pending = useRef<AbortController | null>(null)
  const base = `/workspaces/${encodeURIComponent(workspaceId)}/generation-jobs`
  useEffect(() => () => pending.current?.abort(), [])
  useEffect(() => {
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined
    async function load() {
      try {
        const value = await request<GenerationJob[]>(base, { signal: controller.signal })
        if (!controller.signal.aborted) {
          setJobs(value); setError('')
          if (value.some(active)) timer = setTimeout(() => void load(), 1000)
        }
      } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    }
    void load()
    return () => { controller.abort(); clearTimeout(timer) }
  }, [base, revision, refresh])

  async function cancel(id: string) {
    if (cancelling) return
    const controller = new AbortController()
    pending.current = controller
    setCancelling(id); setError('')
    try {
      await request(`${base}/${encodeURIComponent(id)}/cancel`, { method: 'POST', signal: controller.signal })
      if (!controller.signal.aborted) setRefresh(value => value + 1)
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) setCancelling('') }
  }

  return <section className="draft-section" aria-label="AI 생성 작업">
    <h3>AI 생성 작업</h3>
    <p>최근 20개 작업입니다. 화면 이동은 취소가 아닙니다. 중단해도 이미 전송된 자료나 사용량은 회수되지 않습니다.</p>
    <Button variant="outline" size="sm" onClick={() => setRefresh(value => value + 1)}>작업 새로고침</Button>
    {error && <p role="alert">{error}</p>}
    {jobs.map(job => <div key={job.id} className="evidence-item">
      <p>{job.provider} · {labels[job.status] ?? job.status} · {new Date(job.createdAt).toLocaleString()}</p>
      <small>작업 ID: {job.id}</small>
      {job.error && <p>{job.error}</p>}
      {active(job) && <Button variant="outline" size="sm" disabled={!!cancelling || job.status === 'CANCELLING'} onClick={() => void cancel(job.id)}>생성 중단</Button>}
      {job.status === 'SUCCEEDED' && job.resultCardId && <Button variant="outline" size="sm" disabled={openDisabled} onClick={() => onOpen(job.resultCardId!)}>생성된 초안 열기</Button>}
    </div>)}
  </section>
}
