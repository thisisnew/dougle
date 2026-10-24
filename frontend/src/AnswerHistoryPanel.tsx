import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'
import { activeAnswerJob, type AnswerJob } from './answerJobEvents'
import ConversationShelf from './ConversationShelf'

type Job = AnswerJob & { workspaceId: string; createdAt: string }
type Summary = { id: string; question: string }
type Bundle = { id: string; workspaceId: string; digest: string; payload: {
  question: string; domains: string[]; includeKnowledge: boolean
  items: { label: string; title: string; kind: string; version: number | null; startLine: number; endLine: number; quote: string
    supports: { label: string; name: string; startLine: number; endLine: number; quote: string }[] }[]
} }
type Props = { workspaceId: string; onClose: () => void }
const names: Record<string, string> = { QUEUED: '대기', RUNNING: '생성 중', CANCELLING: '중단 중', CANCELLED: '중단됨', SUCCEEDED: '완료', FAILED: '실패', INTERRUPTED: '앱 재시작으로 중단됨' }

export default function AnswerHistoryPanel(props: Props) {
  return <ScopedHistory key={props.workspaceId} {...props} />
}

function ScopedHistory({ workspaceId, onClose }: Props) {
  const dialog = useRef<HTMLDialogElement>(null)
  const pending = useRef<AbortController | null>(null)
  const [jobs, setJobs] = useState<Job[]>([])
  const [questions, setQuestions] = useState<Record<string, string>>({})
  const [detail, setDetail] = useState<{ job: Job; bundle: Bundle } | null>(null)
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState('ALL')
  const [busy, setBusy] = useState(true)
  const [error, setError] = useState('')
  const [shelfOpen, setShelfOpen] = useState(false)
  const base = `/workspaces/${encodeURIComponent(workspaceId)}`

  async function load(id?: string) {
    pending.current?.abort()
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError(''); setDetail(null)
    try {
      if (id) {
        const job = await request<Job>(`${base}/answer-jobs/${encodeURIComponent(id)}`, { signal: controller.signal })
        if (controller.signal.aborted) return
        if (job.workspaceId !== workspaceId || job.id !== id || !names[job.status]) throw new Error('답변 이력의 범위가 다릅니다.')
        const bundle = await request<Bundle>(`${base}/evidence-bundles/${encodeURIComponent(job.bundleId)}`, { signal: controller.signal })
        if (controller.signal.aborted) return
        if (bundle.workspaceId !== workspaceId || bundle.id !== job.bundleId) throw new Error('근거 묶음의 범위가 다릅니다.')
        setDetail({ job, bundle })
        setJobs(previous => previous.map(value => value.id === id ? job : value))
      } else {
        setJobs([]); setQuestions({})
        const [values, bundles] = await Promise.all([
          request<Job[]>(`${base}/answer-jobs`, { signal: controller.signal }),
          request<Summary[]>(`${base}/evidence-bundles`, { signal: controller.signal }),
        ])
        if (controller.signal.aborted) return
        if (values.some(value => value.workspaceId !== workspaceId || !names[value.status])) throw new Error('답변 이력의 범위가 다릅니다.')
        setJobs(values); setQuestions(Object.fromEntries(bundles.map(bundle => [bundle.id, bundle.question])))
      }
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }

  useEffect(() => {
    const element = dialog.current!
    element.showModal()
    void load()
    return () => { pending.current?.abort(); element.close() }
  }, [])

  const visible = jobs.filter(job => (questions[job.bundleId] || '').toLocaleLowerCase().includes(query.trim().toLocaleLowerCase())
    && (status === 'ALL' || (status === 'ACTIVE' ? activeAnswerJob(job) : job.status === status)))

  return <dialog ref={dialog} className="proposal-merge" aria-label="이전 답변 보기" onCancel={event => { event.preventDefault(); onClose() }}>
    <div className="panel-heading"><h2>이전 답변 보기</h2><Button variant="outline" onClick={onClose}>닫기</Button></div>
    <p>이 서재에서 질문한 내용과 답변을 다시 읽을 수 있습니다. 조회만으로 AI를 호출하지 않습니다.</p>
    <details className="secondary-tools"><summary>대화 정리·이어서 질문하기</summary><p>필요할 때만 이전 답변을 묶어 문맥으로 선택하세요. 새 질문에는 별도 전송 승인이 필요합니다.</p>
    <Button type="button" variant="outline" onClick={() => setShelfOpen(value => !value)}>{shelfOpen ? '보관함 관리 닫기' : '대화 보관함 관리'}</Button>
    {shelfOpen && <ConversationShelf workspaceId={workspaceId}
      selectedJobId={!busy && detail?.job.status === 'SUCCEEDED' ? detail.job.id : null}
      onSelectJob={id => void load(id)} />}</details>
    <Button variant="outline" disabled={busy} onClick={() => void load()}>이력 새로고침</Button>
    <label>이력 질문 검색<input aria-label="이력 질문 검색" maxLength={256} disabled={busy} value={query} onChange={event => { setQuery(event.target.value); setDetail(null) }} /></label>
    <label>이력 상태<select aria-label="이력 상태" disabled={busy} value={status} onChange={event => { setStatus(event.target.value); setDetail(null) }}>
      <option value="ALL">전체</option><option value="ACTIVE">진행 중</option>
      {['SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED'].map(value => <option key={value} value={value}>{names[value]}</option>)}
    </select></label>
    {busy && <p role="status">이력을 불러오는 중…</p>}
    {error && <p role="alert">{error} 삭제되었거나 접근할 수 없는 이력은 목록을 새로고침하세요.</p>}
    {!busy && !error && <p role="status">{jobs.length === 0 ? '저장된 답변 작업이 없습니다.' : `${visible.length}개 표시 · 전체 ${jobs.length}개`}</p>}
    <ul aria-label="질문별 답변 작업">{visible.map(job => <li key={job.id}>
      <Button variant="outline" disabled={busy} aria-pressed={detail?.job.id === job.id} onClick={() => void load(job.id)}>
        {questions[job.bundleId] || '질문 조회 불가'} · {names[job.status]} · {new Date(job.createdAt).toLocaleString('ko-KR')} · {job.id.slice(0, 8)}
      </Button>
    </li>)}</ul>
    {detail && <section aria-label="답변 이력 상세">
      <h3>{detail.bundle.payload.question}</h3>
      <p>{detail.bundle.payload.domains.join(', ') || '전체 도메인'} · {detail.bundle.payload.includeKnowledge ? '게시 지식 포함' : '원본만'}</p>
      <p>조회 시점: {names[detail.job.status]} · {detail.job.provider} · 요청 모델 {detail.job.requestedModel || 'CLI 기본값 (실제 모델 미확인)'}</p>
      {activeAnswerJob(detail.job) && <p>현재 진행 상태는 다시 선택하거나 새로고침하세요. 이 화면을 닫아도 생성은 중단되지 않습니다.</p>}
      <h4>답변 당시 근거</h4>
      <p>당시 복사본이며 최신성·사실성을 보증하지 않습니다. 원본이 삭제되어도 보관될 수 있습니다.</p>
      {detail.bundle.payload.items.map(item => <section key={item.label}>
        <h5>[{item.label}] {item.title} · {item.kind === 'KNOWLEDGE' ? `게시 v${item.version}` : '원본'} · {item.startLine}–{item.endLine}행</h5>
        <pre>{item.quote}</pre>
        {item.supports.map(support => <details key={support.label}><summary>[{item.label}/{support.label}] {support.name} · {support.startLine}–{support.endLine}행</summary><pre>{support.quote}</pre></details>)}
      </section>)}
      <h4>저장된 답변</h4>
      {detail.job.status === 'SUCCEEDED' && detail.job.answer !== null ? <pre aria-label="이력 답변">{detail.job.answer}</pre> : <p>저장된 완료 답변이 없습니다. 임시 출력은 보관하지 않습니다.</p>}
      {detail.job.error && <p role="alert">{detail.job.error}</p>}
      <details><summary>작업·근거 식별 정보</summary><pre>{detail.job.id}{'\n'}{detail.bundle.id}{'\n'}{detail.bundle.digest}</pre></details>
    </section>}
    <p>최대 100개 작업을 조회합니다. 근거 묶음을 삭제하면 연결된 답변 작업과 보관함 연결도 삭제됩니다. 개별 작업 삭제는 아직 지원하지 않습니다.</p>
  </dialog>
}
