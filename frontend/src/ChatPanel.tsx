import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ArrowUp, FileText, ImageIcon, MessageSquare, Plus, X } from 'lucide-react'
import { request } from './api'
import { Button } from './components/ui/button'
import type { ConnectionIndicator } from './connectionIndicator'
import type { AnswerJob } from './answerJobEvents'
import QuickAnswerPanel from './QuickAnswerPanel'
import AnswerJobsPanel from './AnswerJobsPanel'
import { attachmentAccept, useQuestionAttachments } from './useQuestionAttachments'
import Wordmark from './Wordmark'

type Summary = { id: string; question: string }
type Bundle = { id: string; workspaceId: string; digest: string; payload: { question: string; status: string;
  items: { kind: string; label: string; title: string; quote: string; startLine: number; endLine: number; sourceRevision?: string }[] } }

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
    {items.length > 0 && <div className="history-heading"><Button variant="ghost" size="icon" aria-label="새 질문" onClick={() => onSelect(null)}><Plus /></Button></div>}
    {error ? <Button variant="ghost" size="sm" onClick={() => setRetry(value => value + 1)}>목록 다시 불러오기</Button>
      : items.length === 0 ? null : <ul>{items.map(item => <li key={item.id}>
        <button aria-current={selectedId === item.id ? 'page' : undefined} title={item.question} onClick={() => onSelect(item.id)}><MessageSquare size={15} /><span>{item.question}</span></button>
      </li>)}</ul>}
  </section>
}

export default function ChatPanel({ workspaceId, selectedId, connection, onCreated, onSources, onDeleted }: {
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
  const fileInput = useRef<HTMLInputElement>(null)
  const attachments = useQuestionAttachments(workspaceId)
  const base = `/workspaces/${encodeURIComponent(workspaceId)}`
  const connected = connection?.state === 'AUTHENTICATED'
  useEffect(() => {
    if (created.current === selectedId && selectedId) { created.current = null; return }
    attachments.reset()
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
    if (locked.current || attachments.uploading || !connected || !question.trim()) return
    if (connection?.provider !== 'CODEX' && attachments.items.some(item => item.mediaType.startsWith('image/'))) {
      setError('이미지 질문은 Codex를 선택해 주세요.'); return
    }
    locked.current = true
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError(''); setBundle(null); setJob(null)
    try {
      const value = await request<Bundle>(`${base}/evidence-bundles`, { method: 'POST', signal: controller.signal,
        body: JSON.stringify({ question: question.trim(), domains: [], includeKnowledge: false,
          ...(attachments.items.length ? { attachmentIds: attachments.items.map(item => item.id) } : {}) }) })
      if (controller.signal.aborted) return
      if (value.workspaceId !== workspaceId) throw new Error('질문의 서재가 다릅니다.')
      attachments.reset(true); setBundle(value); setQuestion(''); created.current = value.id; onCreated(value.id)
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
    {!bundle && !busy && <div className="chat-welcome"><h1 aria-label="Dougle"><Wordmark /></h1></div>}
    {busy && <p role="status">참고 자료를 확인하고 있습니다…</p>}
    {error && <p role="alert">{error}</p>}
    {bundle && <div className="chat-thread">
      <h2 className="question-bubble">{bundle.payload.question}</h2>
      <section className="chat-evidence" aria-label="참고 자료">
        <h3>참고 자료 <span>{bundle.payload.items.length}</span></h3>
        {bundle.payload.items.map(item => <details key={item.label}><summary>[{item.label}] {item.title}{item.kind === 'ATTACHMENT_IMAGE' ? ' · 첨부 이미지' : item.kind === 'ATTACHMENT_TEXT' ? ' · 첨부 문서' : ''}</summary><p>{item.kind === 'ATTACHMENT_IMAGE' ? '이 이미지를 AI에 함께 보냅니다.' : `${item.startLine}–${item.endLine}행 · 질문 당시 복사본`}{item.sourceRevision === 'TRUNCATED' && ' · 문서 앞부분만 포함'}</p><pre>{item.quote}</pre></details>)}
        {!bundle.payload.items.length && <p>답변의 근거가 될 자료가 없어요. 자료를 추가하거나 질문을 바꿔 주세요.<Button variant="ghost" onClick={onSources}>자료 추가하기</Button></p>}
      </section>
      {job ? <AnswerJobsPanel key={job.id} workspaceId={workspaceId} bundleId={bundle.id} approval={null} initialJob={job} compact />
        : bundle.payload.items.some(item => !['ORIGINAL', 'ATTACHMENT_IMAGE', 'ATTACHMENT_TEXT'].includes(item.kind)) ? <p>이전 지식 문서가 포함된 질문입니다. 원본에서 다시 확인해 주세요.<Button variant="outline" onClick={() => { setQuestion(bundle.payload.question); setBundle(null) }}>원본으로 다시 질문</Button></p>
        : bundle.payload.items.length > 0 && bundle.payload.status !== 'NO_EVIDENCE' && <QuickAnswerPanel key={JSON.stringify([bundle.id, connection?.provider, connection?.model])} workspaceId={workspaceId} bundleId={bundle.id} bundleDigest={bundle.digest}
          connectedProvider={connected ? connection!.provider : null} connectedModel={connection?.model} requireConnection />}
      <details className="secondary-tools"><summary>질문 관리</summary><Button variant="destructive" disabled={busy} onClick={() => void remove()}>질문과 답변 삭제</Button></details>
    </div>}
    <form className="chat-composer" onSubmit={event => void submit(event)} onDragOver={event => { if (event.dataTransfer.types.includes('Files')) event.preventDefault() }} onDrop={event => {
      if (!event.dataTransfer.files.length) return
      event.preventDefault(); if (!busy) void attachments.add(Array.from(event.dataTransfer.files))
    }}>
      <label className="sr-only" htmlFor="chat-question">질문</label>
      <textarea id="chat-question" rows={3} maxLength={256} value={question} disabled={!connected || busy}
        placeholder={connected ? '더글한테 물어보기' : 'AI를 연결해 주세요'} onChange={event => setQuestion(event.target.value)}
        onPaste={event => { if (event.clipboardData.files.length) { event.preventDefault(); void attachments.add(Array.from(event.clipboardData.files)) } }}
        onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); event.currentTarget.form?.requestSubmit() } }} />
      <input ref={fileInput} type="file" className="sr-only" aria-label="질문에 첨부할 파일" multiple accept={attachmentAccept} disabled={busy || attachments.uploading} onChange={event => { void attachments.add(Array.from(event.target.files ?? [])); event.target.value = '' }} />
      {attachments.items.length > 0 && <ul className="question-attachments" aria-label="이 질문의 첨부 파일">{attachments.items.map(item => <li key={item.id}>
        {item.mediaType.startsWith('image/') ? <ImageIcon aria-hidden="true" /> : <FileText aria-hidden="true" />}<span title={item.name}>{item.name}{item.truncated && <small>처음 {item.characters.toLocaleString()}자만 사용</small>}</span>
        <Button type="button" variant="ghost" size="icon" aria-label={`${item.name} 첨부 제거`} disabled={busy || attachments.uploading} onClick={() => void attachments.remove(item.id)}><X /></Button>
      </li>)}</ul>}
      {attachments.uploading && <p role="status">첨부 파일을 읽고 있어요.<Button type="button" variant="ghost" onClick={attachments.cancel}>첨부 중단</Button></p>}
      {attachments.error && <p role="alert">{attachments.error}</p>}
      <div className="composer-actions"><Button variant="ghost" type="button" size="icon" aria-label="파일 첨부" title="이미지·문서 첨부 · 최대 4개, 각각 5 MiB" disabled={busy || attachments.uploading || attachments.items.length >= 4} onClick={() => fileInput.current?.click()}><Plus /></Button><Button type="submit" size="icon" aria-label="질문 보내기" disabled={!connected || busy || attachments.uploading || !question.trim()}><ArrowUp /></Button></div>
    </form>
  </section>
}
