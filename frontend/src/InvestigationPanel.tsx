import { useEffect, useRef, useState } from 'react'
import { ApiError, request } from './api'
import { Button } from './components/ui/button'
import type { ConnectionIndicator } from './connectionIndicator'
import ConversationReadPanel, { type ConversationCapture, type ConversationScope } from './ConversationReadPanel'
import type { ConversationPlan } from './sendConversationPlan'

type Preview = { bundleId?: string; digest: string; prompt: string; characters: number; maxCalls: number; maxToolCalls?: number; provider: string; requestedModel: string; mode?: string; rootQuestionBundleId?: string }
type Job = ConversationPlan
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

export default function InvestigationPanel({ workspaceId, bundleId, threadId, connection, disabled, onAnswer, onCaptured, onBusyChange, initialJob, onPlanChange, onScope, continuationRoot }: {
  workspaceId: string; bundleId: string; connection: ConnectionIndicator | null; disabled: boolean; onAnswer?: (jobId: string) => Promise<void>
  threadId?: string; onCaptured?: (receipt: ConversationCapture, signal: AbortSignal) => Promise<void>; onBusyChange?: (busy: boolean) => void
  initialJob?: Job | null
  onPlanChange?: (job: Job) => void
  onScope?: (scope: ConversationScope | null) => void
  continuationRoot?: string
}) {
  const [open, setOpen] = useState(!!initialJob)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [preview, setPreview] = useState<Preview | null>(null)
  const [job, setJob] = useState<Job | null>(initialJob ?? null)
  const [submission, setSubmission] = useState<Submission | null>(null)
  const [scope, setScope] = useState<ConversationScope | null>(null)
  const [reading, setReading] = useState(false)
  const scopeCallback = useRef(onScope)
  scopeCallback.current = onScope
  const busyCallback = useRef(onBusyChange)
  busyCallback.current = onBusyChange
  const locked = useRef(false)
  const lifetime = useRef(new AbortController())
  const base = `/workspaces/${encodeURIComponent(workspaceId)}/investigation-plans`
  const connected = connection?.state === 'AUTHENTICATED'
  const removed = job?.decision === 'PROPOSE_DATADOG_LOGS'
  const matches = removed ? [] : excerpts(job?.output)
  const proposed = job?.status === 'SUCCEEDED' && ['PROPOSE_MYSQL_READ'].includes(job.decision ?? '')

  useEffect(() => { if (initialJob) { setJob(initialJob); setOpen(true) } }, [initialJob])
  useEffect(() => { busyCallback.current?.(busy || reading || active(job)) }, [busy, reading, job?.status])

  useEffect(() => {
    const controller = new AbortController()
    lifetime.current = controller
    return () => { controller.abort(); busyCallback.current?.(false) }
  }, [])

  function checked(value: Job): Job {
    if (value.workspaceId !== workspaceId || value.bundleId !== bundleId) throw new Error('조사 대상이 다릅니다.')
    if (continuationRoot && value.status === 'SUCCEEDED' && !['STOP', 'PROPOSE_MYSQL_READ'].includes(value.decision ?? '')) {
      throw new Error('추가 범위 확인에서 허용하지 않은 작업입니다. 자료를 다시 검색하거나 조회하지 않습니다.')
    }
    return value
  }

  useEffect(() => {
    setScope(null)
    if (!open || !proposed || job?.toolStatus !== 'PENDING_SCOPE' || !threadId || !onCaptured) return
    const controller = new AbortController()
    void request<ConversationScope>(`${base}/jobs/${encodeURIComponent(job.id)}/clarification`, { signal: controller.signal }).then(value => {
      if (controller.signal.aborted) return
      if (value.jobId !== job.id || value.workspaceId !== workspaceId || value.threadId !== threadId || value.parentBundleId !== bundleId
        || value.executionAllowed !== false || value.sessionOnly !== true || !Array.isArray(value.questions)
        || value.tool !== (job.decision === 'PROPOSE_MYSQL_READ' ? 'MYSQL_READ' : 'DATADOG_LOGS')) throw new Error('조사 대상 대화가 다릅니다.')
      setScope(value); scopeCallback.current?.(value)
    }).catch(reason => { if (!controller.signal.aborted) setError((reason as Error).message) })
    return () => { controller.abort(); scopeCallback.current?.(null) }
  }, [base, open, proposed, job?.id, job?.toolStatus, threadId, bundleId, workspaceId, !!onCaptured])

  useEffect(() => {
    if (!open || !active(job)) return
    const controller = new AbortController()
    const timer = setInterval(() => {
      void request<Job>(`${base}/jobs/${encodeURIComponent(job!.id)}`, { signal: controller.signal })
        .then(value => { if (!controller.signal.aborted) {
          if (value.id !== job!.id) throw new Error('조사 접수 대상이 다릅니다.')
          setJob(checked(value)); onPlanChange?.(value); setError('')
        } })
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
        if (jobs.length) { const value = jobs[jobs.length - 1]; setJob(value); onPlanChange?.(value); return }
      }
      const value = await request<Preview>(`${base}/preview`, { method: 'POST', signal,
        body: JSON.stringify({ bundleId, provider: connection!.provider, requestedModel: connection?.model ?? null }) })
      if (continuationRoot && (value.mode !== 'RESOLVE_CAPTURED_SCOPE' || value.rootQuestionBundleId !== continuationRoot
        || value.bundleId !== bundleId || value.maxCalls !== 1 || value.maxToolCalls !== 0 || value.provider !== connection!.provider
        || (connection?.model != null && value.requestedModel !== connection.model))) throw new Error('추가 범위 확인의 대상 또는 실행 한도가 다릅니다.')
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
      if (!signal.aborted) { const accepted = checked(result); setJob(accepted); onPlanChange?.(accepted); setPreview(null); setSubmission(null) }
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
      if (!signal.aborted) { const value = checked(result); setJob(value); onPlanChange?.(value) }
    } catch (reason) { if (!signal.aborted) setError((reason as Error).message) }
    finally { locked.current = false; if (!signal.aborted) setBusy(false) }
  }

  async function cancelScope() {
    if (!scope || locked.current || disabled || reading) return
    locked.current = true; setBusy(true); setError('')
    const signal = lifetime.current.signal
    try {
      await request(`${base}/jobs/${encodeURIComponent(scope.jobId)}/scope/cancel`, { method: 'POST', signal,
        body: JSON.stringify({ expectedRevision: scope.revision }) })
      if (!signal.aborted) {
        setScope(null); scopeCallback.current?.(null)
        setJob(current => current ? { ...current, status: 'CANCELLED', output: null } : current)
        if (job) onPlanChange?.({ ...job, status: 'CANCELLED', output: null })
      }
    } catch (reason) { if (!signal.aborted) setError((reason as Error).message) }
    finally { locked.current = false; if (!signal.aborted) setBusy(false) }
  }

  return <section className="chat-investigation" aria-label="추가 자료 검색">
    {!open ? <Button variant="ghost" size="sm" disabled={disabled || !connected} onClick={() => void prepare()}>{continuationRoot ? '찾은 자료로 조회 범위 확인' : '자료 더 찾아보기'}</Button>
      : <>
        {busy && <p role="status">확인 중…</p>}
        {error && <p role="alert">{error}</p>}
        {preview && <>
          {continuationRoot ? <p>찾은 자료와 원래 질문을 AI에 한 번 보내 조회 범위를 확인합니다. 이 질문에서는 한 번만 확인하며, 추가 검색이나 DB 조회는 실행하지 않습니다.</p>
            : <p>현재 질문과 근거를 AI에 한 번 보내 추가 검색 필요 여부를 확인합니다. 로컬 자료는 최대 한 번 검색하며 운영 DB는 조회하지 않습니다. 운영 조회 제안이 반환되어도 실행하지 않습니다.</p>}
          <details><summary>전송 내용 확인</summary><pre>{preview.prompt}</pre></details>
          <Button variant="outline" disabled={busy || disabled || !connected} onClick={() => void start()}>{submission ? '접수 상태 확인' : continuationRoot ? '찾은 자료로 조회 범위 확인' : '검색 시작'}</Button>
        </>}
        {job && <>
          {active(job) ? <p role="status">추가 자료를 찾고 있어요. <Button variant="ghost" disabled={busy} onClick={() => void cancel()}>취소</Button></p>
            : <p>{removed ? '제거된 연결 기능의 기록입니다.' : proposed ? '운영 조회 제안 · 실행하지 않음' : job.decision === 'STOP' || job.status === 'STOPPED' ? '추가 검색을 진행하지 않았습니다.' : job.status === 'SUCCEEDED' ? '자료 검색 완료' : job.status === 'CANCELLED' ? '검색 취소됨' : '자료 검색을 완료하지 못했습니다.'}</p>}
          {proposed && !scope && <p>DB 조회는 아직 실행되지 않았습니다.</p>}
          {scope && scope.questions.map(question => <p key={question.topic}>{question.prompt}</p>)}
          {scope && scope.questions.length > 0 && <p>아래 대화 입력창에 답해 주세요. 처음 질문과 함께 확인하겠습니다.</p>}
          {scope && !scope.questions.length && scope.internalResolutionRequired && <p>아직 조회할 자료를 확인하지 못했습니다. 원본은 조회하지 않았습니다.</p>}
          {scope && !scope.internalResolutionRequired && !scope.questions.length && onCaptured && <ConversationReadPanel
            key={`${scope.scopeId}:${scope.revision}`} scope={scope} disabled={disabled || !connected} onCaptured={onCaptured}
            onBusyChange={value => { setReading(value); onBusyChange?.(value) }} />}
          {scope && <Button variant="ghost" disabled={busy || reading || disabled} onClick={() => void cancelScope()}>이번 조회 안 하기</Button>}
          {job.query && <p>검색어: {job.query}</p>}
          {job.error && <p role="alert">{job.error}</p>}
          {job.toolStatus === 'EMPTY' && <p>추가로 일치하는 자료가 없습니다.</p>}
          {job.truncated && <p>검색 결과가 많아 일부만 확인했습니다. 질문의 범위를 좁혀 다시 찾아 주세요.</p>}
          {job.output && !proposed && !removed && <details open><summary>검색 결과 · 답변으로 검증되지 않은 자료</summary>
            {matches.length ? matches.map((match, index) => <div key={index}><p>자료 {index + 1} · {match.startLine}–{match.endLine}행</p><pre>{match.content}</pre></div>)
              : <pre>{job.output}</pre>}
          </details>}
          {job.status === 'SUCCEEDED' && job.decision === 'SEARCH' && job.toolStatus === 'OK' && !job.truncated && onAnswer &&
            <Button variant="outline" disabled={busy || disabled || !connected} onClick={() => void onAnswer(job.id)}>이 자료로 다시 답변</Button>}
          {!active(job) && !continuationRoot && <Button variant="ghost" disabled={busy || reading || disabled || !connected} onClick={() => void prepare(true)}>새 검색 계획 확인</Button>}
        </>}
        {!busy && !preview && !job && <Button variant="outline" onClick={() => void prepare()}>다시 확인</Button>}
        {!initialJob && <Button variant="ghost" disabled={busy || reading} onClick={() => setOpen(false)}>접기</Button>}
      </>}
  </section>
}
