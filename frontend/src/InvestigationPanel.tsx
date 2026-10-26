import { useEffect, useRef, useState } from 'react'
import { ApiError, request } from './api'
import { Button } from './components/ui/button'
import type { ConnectionIndicator } from './connectionIndicator'
import { checkedOperationalProposal, type OperationalToolProposal } from './operationalToolProposal'

type Preview = { digest: string; prompt: string; characters: number; maxCalls: number; provider: string; requestedModel: string }
type Job = { id: string; workspaceId: string; bundleId: string; status: string; decision?: string | null; query?: string | null; output?: string | null; error?: string | null; truncated?: boolean; toolStatus?: string | null }
type Submission = { requestId: string; bundleId: string; provider: string; requestedModel: string | null; digest: string; approved: true }
const active = (job: Job | null) => !!job && ['QUEUED', 'RUNNING', 'CANCELLING'].includes(job.status)

function excerpts(output: string | null | undefined): { content: string; startLine: number; endLine: number }[] {
  if (!output || output.length > 6000) return []
  return output.split('\n').flatMap(line => {
    try {
      const value = JSON.parse(line)
      if (!value || typeof value.content !== 'string' || !Number.isSafeInteger(value.startLine)
        || !Number.isSafeInteger(value.endLine) || value.startLine < 1 || value.endLine < value.startLine) return []
      return [{ content: value.content, startLine: value.startLine, endLine: value.endLine }]
    } catch { return [] }
  })
}

export default function InvestigationPanel({ workspaceId, bundleId, connection, disabled, onAnswer, onOpenOperationalTool }: {
  workspaceId: string; bundleId: string; connection: ConnectionIndicator | null; disabled: boolean; onAnswer?: (jobId: string) => Promise<void>
  onOpenOperationalTool?: (proposal: OperationalToolProposal) => void
}) {
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [preview, setPreview] = useState<Preview | null>(null)
  const [job, setJob] = useState<Job | null>(null)
  const [submission, setSubmission] = useState<Submission | null>(null)
  const locked = useRef(false)
  const lifetime = useRef(new AbortController())
  const current = useRef({ workspaceId, bundleId, disabled, onOpenOperationalTool })
  current.current = { workspaceId, bundleId, disabled, onOpenOperationalTool }
  const base = `/workspaces/${encodeURIComponent(workspaceId)}/investigation-plans`
  const connected = connection?.state === 'AUTHENTICATED'
  const matches = excerpts(job?.output)
  const proposed = job?.status === 'SUCCEEDED' && ['PROPOSE_MYSQL_READ', 'PROPOSE_DATADOG_LOGS'].includes(job.decision ?? '')

  useEffect(() => {
    const controller = new AbortController()
    lifetime.current = controller
    return () => controller.abort()
  }, [])

  function checked(value: Job): Job {
    if (value.workspaceId !== workspaceId || value.bundleId !== bundleId) throw new Error('조사 대상이 다릅니다.')
    return value
  }

  useEffect(() => {
    if (!open || !active(job)) return
    const controller = new AbortController()
    const timer = setInterval(() => {
      void request<Job>(`${base}/jobs/${encodeURIComponent(job!.id)}`, { signal: controller.signal })
        .then(value => { if (!controller.signal.aborted) { setJob(checked(value)); setError('') } })
        .catch(() => { if (!controller.signal.aborted) setError('진행 상태를 확인하지 못했습니다. 다시 확인 중입니다.') })
    }, 1500)
    return () => { clearInterval(timer); controller.abort() }
  }, [base, open, job?.id, job?.status])

  async function prepare(newPlan = false) {
    if (locked.current || disabled || !connected) return
    if (submission || (preview && !newPlan)) { setOpen(true); return }
    locked.current = true; setBusy(true); setError(''); setOpen(true)
    const signal = lifetime.current.signal
    try {
      if (!newPlan) {
        const jobs = await request<Job[]>(`${base}/jobs?bundleId=${encodeURIComponent(bundleId)}`, { signal })
        if (signal.aborted) return
        jobs.forEach(checked)
        if (jobs.length) { setJob(jobs[jobs.length - 1]); return }
      }
      const value = await request<Preview>(`${base}/preview`, { method: 'POST', signal,
        body: JSON.stringify({ bundleId, provider: connection!.provider, requestedModel: connection?.model ?? null }) })
      if (!signal.aborted) { setPreview(value); setJob(null) }
    } catch (reason) { if (!signal.aborted) setError((reason as Error).message) }
    finally { locked.current = false; if (!signal.aborted) setBusy(false) }
  }

  async function start() {
    if (locked.current || disabled || !preview || !connected || active(job)) return
    locked.current = true; setBusy(true); setError('')
    const signal = lifetime.current.signal
    const value = submission ?? { requestId: crypto.randomUUID(), bundleId, provider: preview.provider,
      requestedModel: preview.requestedModel, digest: preview.digest, approved: true as const }
    setSubmission(value)
    try {
      const result = await request<Job>(`${base}/jobs`, { method: 'POST', signal, body: JSON.stringify(value) })
      if (!signal.aborted) { setJob(checked(result)); setPreview(null); setSubmission(null) }
    } catch (reason) {
      if (!signal.aborted) {
        setError((reason as Error).message)
        if (reason instanceof ApiError && [400, 403, 404, 409, 410, 413, 422].includes(reason.status)) {
          setSubmission(null); setPreview(null)
        }
      }
    }
    finally { locked.current = false; if (!signal.aborted) setBusy(false) }
  }

  async function cancel() {
    if (!active(job) || locked.current) return
    locked.current = true; setBusy(true); setError('')
    const signal = lifetime.current.signal
    try {
      const result = await request<Job>(`${base}/jobs/${encodeURIComponent(job!.id)}/cancel`, { method: 'POST', signal })
      if (!signal.aborted) setJob(checked(result))
    } catch (reason) { if (!signal.aborted) setError((reason as Error).message) }
    finally { locked.current = false; if (!signal.aborted) setBusy(false) }
  }

  async function openProposal() {
    if (!proposed || !job || locked.current || disabled || !onOpenOperationalTool) return
    locked.current = true; setBusy(true); setError('')
    const signal = lifetime.current.signal
    try {
      const proposal = checkedOperationalProposal(await request<unknown>(`${base}/jobs/${encodeURIComponent(job.id)}/tool-proposal`, { signal }))
      if (signal.aborted || current.current.disabled || current.current.workspaceId !== workspaceId || current.current.bundleId !== bundleId) return
      if (proposal.workspaceId !== workspaceId || proposal.bundleId !== bundleId || proposal.jobId !== job.id
          || `PROPOSE_${proposal.kind}` !== job.decision) throw new Error('운영 조회 제안의 대상이 다릅니다.')
      current.current.onOpenOperationalTool?.(proposal)
    } catch (reason) { if (!signal.aborted) setError((reason as Error).message) }
    finally { locked.current = false; if (!signal.aborted) setBusy(false) }
  }

  return <section className="chat-investigation" aria-label="추가 자료 검색">
    {!open ? <Button variant="ghost" size="sm" disabled={disabled || !connected} onClick={() => void prepare()}>자료 더 찾아보기</Button>
      : <>
        {busy && <p role="status">확인 중…</p>}
        {error && <p role="alert">{error}</p>}
        {preview && <>
          <p>현재 질문과 근거를 AI에 한 번 보내 로컬 검색어 또는 수동 운영 조회 제안을 정합니다. 로컬 자료는 최대 한 번 검색하며 운영 DB·로그는 조회하지 않습니다. 제안의 조회 범위는 직접 입력하고 별도로 승인해야 합니다.</p>
          <details><summary>전송 내용 확인</summary><pre>{preview.prompt}</pre></details>
          <Button variant="outline" disabled={busy || disabled || !connected} onClick={() => void start()}>{submission ? '접수 상태 확인' : '검색 시작'}</Button>
        </>}
        {job && <>
          {active(job) ? <p role="status">추가 자료를 찾고 있어요. <Button variant="ghost" disabled={busy} onClick={() => void cancel()}>취소</Button></p>
            : <p>{proposed ? '수동 조회 제안 · 아직 조회하지 않음' : job.decision === 'STOP' || job.status === 'STOPPED' ? '추가 검색을 진행하지 않았습니다.' : job.status === 'SUCCEEDED' ? '자료 검색 완료' : job.status === 'CANCELLED' ? '검색 취소됨' : '자료 검색을 완료하지 못했습니다.'}</p>}
          {proposed && <><p>{job.decision === 'PROPOSE_MYSQL_READ' ? 'MySQL 데이터' : 'Datadog 로그'} 조회를 제안합니다. 연결 가능 여부는 확인하지 않았으며 대상·조건은 직접 입력해야 합니다.</p>
            {onOpenOperationalTool && <Button variant="outline" disabled={busy || disabled} onClick={() => void openProposal()}>조회 양식 열기</Button>}</>}
          {job.query && <p>검색어: {job.query}</p>}
          {job.error && <p role="alert">{job.error}</p>}
          {job.toolStatus === 'EMPTY' && <p>추가로 일치하는 자료가 없습니다.</p>}
          {job.truncated && <p>표시 한도로 검색 결과 일부만 표시합니다.</p>}
          {job.output && <details open><summary>검색 결과 · 답변으로 검증되지 않은 자료</summary>
            {matches.length ? matches.map((match, index) => <div key={index}><p>자료 {index + 1} · {match.startLine}–{match.endLine}행</p><pre>{match.content}</pre></div>)
              : <pre>{job.output}</pre>}
          </details>}
          {job.status === 'SUCCEEDED' && job.decision === 'SEARCH' && job.toolStatus === 'OK' && onAnswer &&
            <Button variant="outline" disabled={busy || disabled || !connected} onClick={() => void onAnswer(job.id)}>이 자료로 다시 답변</Button>}
          {!active(job) && <Button variant="ghost" disabled={busy || disabled || !connected} onClick={() => void prepare(true)}>새 검색 계획 확인</Button>}
        </>}
        {!busy && !preview && !job && <Button variant="outline" onClick={() => void prepare()}>다시 확인</Button>}
        <Button variant="ghost" disabled={busy} onClick={() => setOpen(false)}>접기</Button>
      </>}
  </section>
}
