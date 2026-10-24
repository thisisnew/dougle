import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ArrowUp, MessageSquare, Plus } from 'lucide-react'
import { request } from './api'
import { Button } from './components/ui/button'
import type { ConnectionIndicator } from './connectionIndicator'
import type { AnswerJob } from './answerJobEvents'
import QuickAnswerPanel from './QuickAnswerPanel'
import AnswerJobsPanel from './AnswerJobsPanel'

type Summary = { id: string; question: string }
type Bundle = { id: string; workspaceId: string; digest: string; payload: { question: string; status: string;
  items: { label: string; title: string; quote: string; startLine: number; endLine: number }[] } }

export function QuestionHistory({ workspaceId, revision, selectedId, onSelect }: {
  workspaceId: string; revision: number; selectedId: string | null; onSelect: (id: string | null) => void
}) {
  const [items, setItems] = useState<Summary[]>([])
  const [error, setError] = useState(false)
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setError(false)
    request<Summary[]>(`/workspaces/${encodeURIComponent(workspaceId)}/evidence-bundles`, { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setItems(value) })
      .catch(() => { if (!controller.signal.aborted) setError(true) })
    return () => controller.abort()
  }, [workspaceId, revision, retry])
  return <section className="question-history" aria-label="최근 질문">
    <div className="history-heading">최근 질문<Button variant="ghost" size="icon" aria-label="새 질문" onClick={() => onSelect(null)}><Plus /></Button></div>
    {error ? <Button variant="ghost" size="sm" onClick={() => setRetry(value => value + 1)}>목록 다시 불러오기</Button>
      : items.length === 0 ? <p>첫 질문을 시작해 보세요.</p> : <ul>{items.map(item => <li key={item.id}>
        <button aria-current={selectedId === item.id ? 'page' : undefined} title={item.question} onClick={() => onSelect(item.id)}><MessageSquare size={15} /><span>{item.question}</span></button>
      </li>)}</ul>}
  </section>
}

export default function ChatPanel({ workspaceId, selectedId, connection, onCreated, onConnect, onSources, onDeleted }: {
  workspaceId: string; selectedId: string | null; connection: ConnectionIndicator | null;
  onCreated: (id: string) => void; onConnect: () => void; onSources: () => void; onDeleted?: () => void
}) {
  const [question, setQuestion] = useState('')
  const [bundle, setBundle] = useState<Bundle | null>(null)
  const [job, setJob] = useState<AnswerJob | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const pending = useRef<AbortController | null>(null)
  const locked = useRef(false)
  const created = useRef<string | null>(null)
  const base = `/workspaces/${encodeURIComponent(workspaceId)}`
  const connected = connection?.state === 'AUTHENTICATED'
  useEffect(() => {
    if (created.current === selectedId && selectedId) { created.current = null; return }
    pending.current?.abort()
    const controller = new AbortController()
    pending.current = controller
    setBundle(null); setJob(null); setError(''); setQuestion(''); setBusy(!!selectedId)
    locked.current = !!selectedId
    if (selectedId) void (async () => {
      try {
        const [value, jobs] = await Promise.all([
          request<Bundle>(`${base}/evidence-bundles/${encodeURIComponent(selectedId)}`, { signal: controller.signal }),
          request<(AnswerJob & { workspaceId: string })[]>(`${base}/answer-jobs`, { signal: controller.signal }),
        ])
        if (controller.signal.aborted) return
        if (value.id !== selectedId || value.workspaceId !== workspaceId || jobs.some(item => item.workspaceId !== workspaceId)) throw new Error('질문의 서재가 다릅니다.')
        setBundle(value); setJob(jobs.find(item => item.bundleId === selectedId) ?? null)
      } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
      finally { if (!controller.signal.aborted) { locked.current = false; setBusy(false) } }
    })()
    return () => controller.abort()
  }, [base, selectedId, workspaceId])
  useEffect(() => () => pending.current?.abort(), [])

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (locked.current || !connected || !question.trim()) return
    locked.current = true
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError(''); setBundle(null); setJob(null)
    try {
      const value = await request<Bundle>(`${base}/evidence-bundles`, { method: 'POST', signal: controller.signal,
        body: JSON.stringify({ question: question.trim(), domains: [], includeKnowledge: true }) })
      if (controller.signal.aborted) return
      if (value.workspaceId !== workspaceId) throw new Error('질문의 서재가 다릅니다.')
      setBundle(value); setQuestion(''); created.current = value.id; onCreated(value.id)
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) { locked.current = false; setBusy(false) } }
  }

  async function remove() {
    if (!bundle || locked.current || !window.confirm('이 질문과 연결된 답변을 삭제할까요? 원본 자료는 유지됩니다.')) return
    const controller = new AbortController()
    pending.current = controller
    locked.current = true; setBusy(true); setError('')
    try {
      await request(`${base}/evidence-bundles/${encodeURIComponent(bundle.id)}`, { method: 'DELETE', signal: controller.signal })
      if (!controller.signal.aborted) { setBundle(null); setJob(null); onDeleted?.() }
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) { locked.current = false; setBusy(false) } }
  }

  return <section className={`chat-panel ${bundle ? 'has-question' : ''}`} aria-label="AI 대화">
    {!bundle && !busy && <div className="chat-welcome"><span className="pixel-spark" aria-hidden="true">✦</span><h1>내 자료에 물어보세요</h1><p>흩어진 자료에서, 필요한 답을.</p></div>}
    {busy && <p role="status">참고 자료를 확인하고 있습니다…</p>}
    {error && <p role="alert">{error}</p>}
    {bundle && <div className="chat-thread">
      <h2 className="question-bubble">{bundle.payload.question}</h2>
      <section className="chat-evidence" aria-label="참고 자료">
        <h3>참고 자료 <span>{bundle.payload.items.length}</span></h3>
        {bundle.payload.items.map(item => <details key={item.label}><summary>[{item.label}] {item.title}</summary><p>{item.startLine}–{item.endLine}행 · 질문 당시 복사본</p><pre>{item.quote}</pre></details>)}
        {!bundle.payload.items.length && <p>답변의 근거가 될 자료가 없어요. 자료를 추가하거나 질문을 바꿔 주세요.<Button variant="ghost" onClick={onSources}>자료 추가하기</Button></p>}
      </section>
      {job ? <AnswerJobsPanel key={job.id} workspaceId={workspaceId} bundleId={bundle.id} approval={null} initialJob={job} compact />
        : bundle.payload.items.length > 0 && bundle.payload.status !== 'NO_EVIDENCE' && <QuickAnswerPanel key={JSON.stringify([bundle.id, connection?.provider, connection?.model])} workspaceId={workspaceId} bundleId={bundle.id} bundleDigest={bundle.digest}
          connectedProvider={connected ? connection!.provider : null} connectedModel={connection?.model} requireConnection />}
      <details className="secondary-tools"><summary>질문 관리</summary><Button variant="destructive" disabled={busy} onClick={() => void remove()}>질문과 답변 삭제</Button></details>
    </div>}
    <form className="chat-composer" onSubmit={event => void submit(event)}>
      <label className="sr-only" htmlFor="chat-question">질문</label>
      <textarea id="chat-question" rows={3} maxLength={256} value={question} disabled={!connected || busy}
        placeholder={connected ? '자료에 대해 궁금한 점을 물어보세요' : 'AI를 연결하면 질문할 수 있어요'} onChange={event => setQuestion(event.target.value)}
        onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); event.currentTarget.form?.requestSubmit() } }} />
      <div className="composer-actions"><Button variant="ghost" type="button" onClick={onSources}><Plus /> 자료 추가</Button><span>내 자료 기반 · 새 질문</span><Button type="submit" size="icon" aria-label="질문 보내기" disabled={!connected || busy || !question.trim()}><ArrowUp /></Button></div>
    </form>
    {!connected ? <div className="chat-connect"><span>먼저 사용할 AI를 연결해 주세요.</span><Button variant="outline" onClick={onConnect}>AI 연결하기</Button></div>
      : <p className="chat-footnote">참고 자료를 먼저 확인합니다. 외부 전송은 동의 후에만 진행됩니다.</p>}
  </section>
}
