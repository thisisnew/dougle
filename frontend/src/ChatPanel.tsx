import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ArrowUp, FileText, ImageIcon, MessageSquare, Plus, X } from 'lucide-react'
import { request } from './api'
import { Button } from './components/ui/button'
import type { ConnectionIndicator } from './connectionIndicator'
import type { AnswerJob } from './answerJobEvents'
import { sendChatAnswer, type ChatSubmission } from './sendChatAnswer'
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

export default function ChatPanel({ workspaceId, selectedId, connection, onCreated, searchState = 'READY' }: {
  workspaceId: string; selectedId: string | null; connection: ConnectionIndicator | null;
  onCreated: (id: string) => void; onConnect: () => void; onSources: () => void
  searchState?: string
}) {
  const [question, setQuestion] = useState('')
  const [bundle, setBundle] = useState<Bundle | null>(null)
  const [job, setJob] = useState<AnswerJob | null>(null)
  const [previousTurns, setPreviousTurns] = useState<{ bundle: Bundle; job: AnswerJob | null; fresh: boolean }[]>([])
  const [busy, setBusy] = useState(false)
  const [submission, setSubmission] = useState<ChatSubmission | null>(null)
  const [fresh, setFresh] = useState(false)
  const [sentQuestion, setSentQuestion] = useState('')
  const [error, setError] = useState('')
  const pending = useRef<AbortController | null>(null)
  const locked = useRef(false)
  const created = useRef<string | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const attachments = useQuestionAttachments(workspaceId)
  const base = `/workspaces/${encodeURIComponent(workspaceId)}`
  const connected = connection?.state === 'AUTHENTICATED'
  const searchReady = searchState === 'READY'
  useEffect(() => {
    if (created.current === selectedId && selectedId) { created.current = null; return }
    attachments.reset()
    setPreviousTurns([])
    pending.current?.abort()
    const controller = new AbortController()
    pending.current = controller
    setBundle(null); setJob(null); setSubmission(null); setFresh(false); setSentQuestion(''); setError(''); setQuestion(''); setBusy(!!selectedId)
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
    if (locked.current || attachments.uploading || !connected || !searchReady || !question.trim()) return
    if (connection?.provider !== 'CODEX' && attachments.items.some(item => item.mediaType.startsWith('image/'))) {
      setError('이미지 질문은 Codex를 선택해 주세요.'); return
    }
    locked.current = true
    const controller = new AbortController()
    pending.current = controller
    if (bundle) setPreviousTurns(turns => [...turns, { bundle, job, fresh }])
    setBusy(true); setError(''); setBundle(null); setJob(null); setSubmission(null); setFresh(true); setSentQuestion(question.trim())
    try {
      const value = await request<Bundle>(`${base}/evidence-bundles`, { method: 'POST', signal: controller.signal,
        body: JSON.stringify({ question: question.trim(), domains: [], includeKnowledge: false,
          ...(attachments.items.length ? { attachmentIds: attachments.items.map(item => item.id) } : {}) }) })
      if (controller.signal.aborted) return
      if (value.workspaceId !== workspaceId) throw new Error('질문의 서재가 다릅니다.')
      attachments.reset(true); setBundle(value); setQuestion(''); created.current = value.id; onCreated(value.id)
      if (value.payload.items.length && value.payload.status !== 'NO_EVIDENCE'
        && value.payload.items.every(item => ['ORIGINAL', 'ATTACHMENT_IMAGE', 'ATTACHMENT_TEXT'].includes(item.kind))) {
        setJob(await sendChatAnswer(workspaceId, value.id, value.digest, connection!.provider, connection?.model ?? null, controller.signal, setSubmission))
      }
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) { locked.current = false; setBusy(false) } }
  }

  async function retryAnswer() {
    if (!bundle || locked.current || !connected || bundle.payload.items.some(item => !['ORIGINAL', 'ATTACHMENT_IMAGE', 'ATTACHMENT_TEXT'].includes(item.kind))) return
    locked.current = true
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError(''); setFresh(true)
    try {
      setJob(await sendChatAnswer(workspaceId, bundle.id, bundle.digest, connection!.provider, connection?.model ?? null,
        controller.signal, setSubmission, submission ?? undefined))
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) { locked.current = false; setBusy(false) } }
  }

  const activeBundleId = bundle?.id
  return <section className={`chat-panel ${bundle || previousTurns.length ? 'has-question' : ''}`} aria-label="AI 대화">
    {!bundle && !busy && previousTurns.length === 0 && <div className="chat-welcome"><h2 aria-label="Dougle"><Wordmark mascot /></h2></div>}
    {error && <p role="alert">{error}</p>}
    {[...previousTurns, ...(bundle ? [{ bundle, job, fresh }] : [])].map(turn => {
      const { bundle: turnBundle, job: turnJob, fresh: turnFresh } = turn
      const current = turnBundle.id === activeBundleId
      const bundle = turnBundle
      const job = turnJob
      const references = (answer: string) => {
        const cited = new Set([...answer.replace(/```[\s\S]*?```/g, '').matchAll(/\[(R\d+)\]/g)].map(match => match[1]))
        const items = bundle.payload.items.filter(item => cited.has(item.label))
        if (!items.length) return null
        return <section className="chat-evidence" aria-label="참고 자료">
          <h3>참고 자료 <span>{items.length}</span></h3>
          {items.map(item => <details key={item.label}><summary>[{item.label}] {item.title}{item.kind === 'ATTACHMENT_IMAGE' ? ' · 첨부 이미지' : item.kind === 'ATTACHMENT_TEXT' ? ' · 첨부 문서' : ''}</summary><p>{item.kind === 'ATTACHMENT_IMAGE' ? '답변에 사용한 첨부 이미지' : `${item.startLine}–${item.endLine}행 · 질문 당시 복사본`}{item.sourceRevision === 'TRUNCATED' && ' · 문서 앞부분만 포함'}</p><pre>{item.quote}</pre></details>)}
        </section>
      }
      return <div className="chat-thread" key={bundle.id}>
      <h2 className="question-bubble">{bundle.payload.question}</h2>
      {current && busy && <p role="status" className="chat-thinking">확인해보겠습니다<span aria-hidden="true">…</span></p>}
      {job ? <AnswerJobsPanel key={job.id} workspaceId={workspaceId} bundleId={bundle.id} approval={null} initialJob={job} compact animate={turnFresh} references={references} />
        : current && bundle.payload.items.some(item => !['ORIGINAL', 'ATTACHMENT_IMAGE', 'ATTACHMENT_TEXT'].includes(item.kind)) ? <p>이전 지식 문서가 포함된 질문입니다. 원본에서 다시 확인해 주세요.<Button variant="outline" onClick={() => { setQuestion(bundle.payload.question); setBundle(null) }}>원본으로 다시 질문</Button></p>
        : current && !busy && bundle.payload.items.length > 0 && bundle.payload.status !== 'NO_EVIDENCE' && <Button disabled={!connected} onClick={() => void retryAnswer()}>{submission ? '접수 상태 다시 확인' : '답변 받기'}</Button>}
      {!bundle.payload.items.length && <p>질문과 관련된 내용을 찾지 못했어요.</p>}

    </div>})}
    {busy && !bundle && <div className="chat-thread"><h2 className="question-bubble">{sentQuestion}</h2><p role="status" className="chat-thinking">확인해보겠습니다<span aria-hidden="true">…</span></p></div>}
    <form className="chat-composer" onSubmit={event => void submit(event)} onDragOver={event => { if (event.dataTransfer.types.includes('Files')) event.preventDefault() }} onDrop={event => {
      if (!event.dataTransfer.files.length) return
      event.preventDefault(); if (!busy) void attachments.add(Array.from(event.dataTransfer.files))
    }}>
      <label className="sr-only" htmlFor="chat-question">질문</label>
      {!searchReady && connected && <p role="status" className="chat-thinking">{searchState === 'ERROR' ? '검색 준비에 실패했습니다. 앱을 다시 실행해 주세요.' : '자료 검색을 준비하고 있어요…'}</p>}
      <textarea id="chat-question" rows={3} maxLength={256} value={question} disabled={!connected || !searchReady || busy}
        placeholder={!connected ? 'AI를 연결해 주세요' : !searchReady ? '검색 준비가 끝나면 질문할 수 있어요' : '더글한테 물어보기'} onChange={event => setQuestion(event.target.value)}
        onPaste={event => { if (event.clipboardData.files.length) { event.preventDefault(); void attachments.add(Array.from(event.clipboardData.files)) } }}
        onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); event.currentTarget.form?.requestSubmit() } }} />
      <input ref={fileInput} type="file" className="sr-only" aria-label="질문에 첨부할 파일" multiple accept={attachmentAccept} disabled={busy || attachments.uploading} onChange={event => { void attachments.add(Array.from(event.target.files ?? [])); event.target.value = '' }} />
      {attachments.items.length > 0 && <ul className="question-attachments" aria-label="이 질문의 첨부 파일">{attachments.items.map(item => <li key={item.id}>
        {item.mediaType.startsWith('image/') ? <ImageIcon aria-hidden="true" /> : <FileText aria-hidden="true" />}<span title={item.name}>{item.name}{item.truncated && <small>처음 {item.characters.toLocaleString()}자만 사용</small>}</span>
        <Button type="button" variant="ghost" size="icon" aria-label={`${item.name} 첨부 제거`} disabled={busy || attachments.uploading} onClick={() => void attachments.remove(item.id)}><X /></Button>
      </li>)}</ul>}
      {attachments.uploading && <p role="status">첨부 파일을 읽고 있어요.<Button type="button" variant="ghost" onClick={attachments.cancel}>첨부 중단</Button></p>}
      {attachments.error && <p role="alert">{attachments.error}</p>}
      <div className="composer-actions"><Button variant="ghost" type="button" size="icon" aria-label="파일 첨부" title="이미지·문서 첨부 · 최대 4개, 각각 5 MiB" disabled={busy || attachments.uploading || attachments.items.length >= 4} onClick={() => fileInput.current?.click()}><Plus /></Button><Button type="submit" size="icon" aria-label="질문 보내기" disabled={!connected || !searchReady || busy || attachments.uploading || !question.trim()}><ArrowUp /></Button></div>
    </form>
  </section>
}
