import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'
import { observeAnswerJob, activeAnswerJob as active, type AnswerJob as Job } from './answerJobEvents'

const names: Record<string, string> = { QUEUED: '대기', RUNNING: '답변 생성 중', CANCELLING: '중단 중', CANCELLED: '중단됨', SUCCEEDED: '답변 생성 완료', FAILED: '실패', INTERRUPTED: '앱 재시작으로 중단됨' }

type Props = {
  workspaceId: string; bundleId: string; approval: { id: string; digest: string } | null
  initialJob?: Job; compact?: boolean
}
export default function AnswerJobsPanel(props: Props) {
  return <ScopedAnswerJobsPanel key={`${props.workspaceId}/${props.bundleId}`} {...props} />
}
function ScopedAnswerJobsPanel({ workspaceId, bundleId, approval, initialJob, compact = false }: Props) {
  const [jobs, setJobs] = useState<Job[]>(initialJob ? [initialJob] : [])
  const [job, setJob] = useState<Job | null>(initialJob ?? null)
  const [agreed, setAgreed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [observation, setObservation] = useState('')
  const [partial, setPartial] = useState('')
  const requestId = useRef<{ approvalId: string; id: string } | null>(null)
  const pending = useRef<AbortController | null>(null)
  const base = `/workspaces/${encodeURIComponent(workspaceId)}`
  useEffect(() => () => pending.current?.abort(), [])
  useEffect(() => {
    if (!active(job) || busy) return
    return observeAnswerJob(workspaceId, bundleId, job!.id, {
      job: value => {
        setJob(value)
        setJobs(previous => previous.map(item => item.id === value.id ? value : item))
      }, state: setObservation, error: setError, partial: setPartial,
    })
  }, [workspaceId, bundleId, job?.id, busy])

  async function act(action: 'list' | 'start' | 'cancel') {
    if (busy || (action === 'start' && (!approval || !agreed)) || (action === 'cancel' && !active(job))) return
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError(''); setObservation(''); setPartial(''); setAgreed(false)
    try {
      if (action === 'list') {
        const values = await request<Job[]>(`${base}/answer-jobs`, { signal: controller.signal })
        if (!controller.signal.aborted) {
          const filtered = values.filter(value => value.bundleId === bundleId)
          setJobs(filtered); setJob(filtered.find(value => value.id === job?.id) ?? filtered[0] ?? null)
        }
      } else {
        if (action === 'start' && requestId.current?.approvalId !== approval!.id) requestId.current = { approvalId: approval!.id, id: crypto.randomUUID() }
        const path = action === 'start' ? `${base}/evidence-bundles/${encodeURIComponent(bundleId)}/answer-jobs` : `${base}/answer-jobs/${encodeURIComponent(job!.id)}/cancel`
        const value = await request<Job>(path, { method: 'POST', signal: controller.signal,
          ...(action === 'start' ? { body: JSON.stringify({ requestId: requestId.current!.id, approvalId: approval!.id, digest: approval!.digest, approved: true }) } : {}) })
        if (!controller.signal.aborted) {
          if (value.bundleId !== bundleId) throw new Error('답변 작업의 근거 묶음이 다릅니다.')
          setJob(value); setJobs(previous => [value, ...previous.filter(item => item.id !== value.id)])
        }
      }
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }

  return <section aria-label="답변 작업" className="answer-approval">
    <h3>{compact ? 'AI 답변' : '실제 답변 요청'}</h3>
    {!compact && <p>아래 전송 버튼은 외부 AI를 호출합니다. Claude 또는 전용 스트리밍 설정을 켠 Codex가 글 조각을 보내면 미검증 임시 출력을 표시합니다. Codex 기본 설정은 완료 답변만 표시합니다.</p>}
    <p>닫거나 다른 Workspace로 이동해도 작업은 계속됩니다. 중단하려면 답변 중단을 누르세요. 이미 전송된 자료는 회수할 수 없습니다.</p>
    {approval && <><label><input type="checkbox" checked={agreed} disabled={busy || active(job)} onChange={event => setAgreed(event.target.checked)} />승인한 요청을 지금 외부 AI에 전송합니다.</label>
      <Button disabled={busy || active(job) || !agreed} onClick={() => void act('start')}>승인한 요청 전송·답변 시작</Button></>}
    {(!compact || error) && <Button variant="outline" disabled={busy} onClick={() => void act('list')}>답변 작업 조회·새로고침</Button>}
    {!compact && jobs.length > 0 && <label>저장된 답변 작업<select aria-label="저장된 답변 작업" value={job?.id || ''} disabled={busy} onChange={event => { setAgreed(false); setError(''); setObservation(''); setJob(jobs.find(value => value.id === event.target.value) ?? null) }}>
      {jobs.map(value => <option key={value.id} value={value.id}>{value.id} · {names[value.status] || value.status}</option>)}
    </select></label>}
    {observation && (compact ? <details><summary>연결 상태</summary><p role="status" aria-label="SSE 연결 상태">{observation}</p></details> : <p role="status" aria-label="SSE 연결 상태">{observation}</p>)}
    {partial && active(job) && <div aria-label="미검증 임시 출력"><p>생성 중 임시 출력 — 인용·사실성 미검증. 최종 답변과 다를 수 있으며 저장하지 않습니다.</p><pre>{partial}</pre></div>}
    {job && <div><p role="status">{names[job.status] || job.status}</p><p>{job.provider} · 요청 모델 {job.requestedModel || 'CLI 기본값 (실제 모델 미확인)'}</p>
      {active(job) && <Button disabled={busy || job.status === 'CANCELLING'} variant="outline" onClick={() => void act('cancel')}>답변 중단</Button>}
      {job.answer && <><p>생성 당시 근거 기준의 AI 답변입니다. 사실성은 별도 확인하세요. 지식 문서로 자동 게시하지 않습니다.</p><pre aria-label="AI 답변">{job.answer}</pre></>}
      {job.error && <p role="alert">{job.error}</p>}
    </div>}
    {error && <p role="alert">{error} 작업 목록에서 서버 상태를 확인하세요. 같은 화면의 재시도는 같은 요청 ID를 사용합니다.</p>}
  </section>
}
