import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ArrowUp, FileText, ImageIcon, MessageSquare, Plus, X } from 'lucide-react'
import { request } from './api'
import { Button } from './components/ui/button'
import type { ConnectionIndicator } from './connectionIndicator'
import { activeAnswerJob, type AnswerJob } from './answerJobEvents'
import { sendChatAnswer, type ChatSubmission } from './sendChatAnswer'
import { readChatSubmission, saveChatSubmission, removeChatSubmission } from './chatSubmissionStorage'
import AnswerJobsPanel from './AnswerJobsPanel'
import { attachmentAccept, useQuestionAttachments } from './useQuestionAttachments'
import Wordmark from './Wordmark'
import InvestigationPanel from './InvestigationPanel'
import BrowserInvestigationPanel from './BrowserInvestigationPanel'
import type { ConversationCapture, ConversationScope } from './ConversationReadPanel'
import { readConversationPlan, sendConversationPlan, type ConversationPlan, type PlanSubmission } from './sendConversationPlan'
import { readPlanSubmission, savePlanSubmission, removePlanSubmission } from './planSubmissionStorage'

type Summary = { id: string; question: string }
type Bundle = { id: string; workspaceId: string; digest: string; payload: { question: string; status: string; chatContext?: { threadId: string; extractive?: unknown } | null;
  items: { kind: string; label: string; title: string; quote: string; startLine: number; endLine: number; sourceRevision?: string }[] } }

function hasOmittedContext(value: unknown): boolean {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const context = value as Record<string, unknown>
  const count = (number: unknown): number is number => typeof number === 'number' && Number.isSafeInteger(number) && number >= 0
  const text = (entry: unknown): entry is string => typeof entry === 'string' && entry.length > 0
  if (context.version !== 1 || !count(context.totalQuestions) || !count(context.omittedQuestions)
    || context.omittedQuestions > context.totalQuestions || !count(context.omittedAnswers)
    || !count(context.maxSerializedCharacters) || context.maxSerializedCharacters === 0
    || !count(context.answerCharacterLimit) || context.answerCharacterLimit === 0
    || !Array.isArray(context.truncatedAnswerBundleIds) || !context.truncatedAnswerBundleIds.every(text)
    || !Array.isArray(context.retrievalQueries) || !context.retrievalQueries.every(text)
    || !Array.isArray(context.questions) || !context.questions.every(entry => entry && typeof entry === 'object'
      && text(entry.bundleId) && typeof entry.bundleDigest === 'string' && /^[a-f0-9]{64}$/.test(entry.bundleDigest) && text(entry.question))) return false
  return context.omittedQuestions > 0 || context.omittedAnswers > 0 || context.truncatedAnswerBundleIds.length > 0
}

export function QuestionHistory({ workspaceId, revision, selectedId, onSelect }: {
  workspaceId: string; revision: number; selectedId: string | null; onSelect: (id: string | null) => void
}) {
  const [items, setItems] = useState<Summary[]>([])
  const [error, setError] = useState(false)
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setError(false)
    request<Summary[]>(`/workspaces/${encodeURIComponent(workspaceId)}/chat-threads`, { signal: controller.signal })
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
  const [investigating, setInvestigating] = useState(false)
  const [plan, setPlan] = useState<ConversationPlan | null>(null)
  const [planPending, setPlanPending] = useState(false)
  const [historyPlanUnknown, setHistoryPlanUnknown] = useState(false)
  const [clarification, setClarification] = useState<ConversationScope | null>(null)
  const planSubmission = useRef<PlanSubmission | undefined>(undefined)
  const [submission, setSubmission] = useState<ChatSubmission | null>(null)
  const [canAbandonAnswer, setCanAbandonAnswer] = useState(false)
  const [fresh, setFresh] = useState(false)
  const [sentQuestion, setSentQuestion] = useState('')
  const [error, setError] = useState('')
  const pending = useRef<AbortController | null>(null)
  const locked = useRef(false)
  const created = useRef<string | null>(null)
  const threadId = useRef<string | null>(selectedId)
  const turnRequest = useRef<{ key: string; requestId: string } | null>(null)
  const captureRequest = useRef<{ jobId: string; requestId: string } | null>(null)
  const operationalTurn = useRef<{ key: string; requestId: string } | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const attachments = useQuestionAttachments(workspaceId)
  const base = `/workspaces/${encodeURIComponent(workspaceId)}`
  const connected = connection?.state === 'AUTHENTICATED'
  const searchReady = searchState === 'READY'
  const activePlan = !!plan && ['QUEUED', 'RUNNING', 'CANCELLING'].includes(plan.status)
  const answerPlanResolved = !plan || (['SUCCEEDED', 'STOPPED'].includes(plan.status) && plan.decision === 'STOP')
  const pendingAnswer = !!bundle && !job && answerPlanResolved && !planPending && bundle.payload.status !== 'NO_EVIDENCE' && bundle.payload.items.length > 0
    && bundle.payload.items.every(item => ['ORIGINAL', 'ATTACHMENT_IMAGE', 'ATTACHMENT_TEXT'].includes(item.kind))
  useEffect(() => {
    if (created.current === selectedId && selectedId) { created.current = null; return }
    created.current = null
    threadId.current = selectedId
    turnRequest.current = null
    operationalTurn.current = null
    setInvestigating(false)
    setPlan(null); setPlanPending(false); setHistoryPlanUnknown(false); planSubmission.current = undefined
    setClarification(null)
    attachments.reset()
    setPreviousTurns([])
    pending.current?.abort()
    const controller = new AbortController()
    pending.current = controller
    setBundle(null); setJob(null); setSubmission(null); setCanAbandonAnswer(false); setFresh(false); setSentQuestion(''); setError(''); setQuestion(''); setBusy(!!selectedId)
    locked.current = !!selectedId
    if (selectedId) void (async () => {
      try {
        const detail = await request<{ id: string; bundles: Bundle[]; jobs: (AnswerJob & { workspaceId: string })[] }>(`${base}/chat-threads/${encodeURIComponent(selectedId)}`, { signal: controller.signal })
        if (controller.signal.aborted) return
        if (detail.id !== selectedId || !detail.bundles.length || detail.bundles.some(item => item.workspaceId !== workspaceId)
          || detail.jobs.some(item => item.workspaceId !== workspaceId)) throw new Error('질문의 서재가 다릅니다.')
        const turns = detail.bundles.map(value => ({ bundle: value, job: detail.jobs.find(item => item.bundleId === value.id) ?? null, fresh: false }))
        const current = turns.pop()!
        setPreviousTurns(turns); setBundle(current.bundle); setJob(current.job)
        setPlanPending(true); setHistoryPlanUnknown(true)
        const planScope = { workspaceId, bundleId: current.bundle.id, bundleDigest: current.bundle.digest }
        planSubmission.current = readPlanSubmission(planScope)
        const restoredPlan = await readConversationPlan(workspaceId, current.bundle.id, controller.signal)
        if (controller.signal.aborted) return
        if (planSubmission.current && restoredPlan && (restoredPlan.id !== planSubmission.current.requestId || restoredPlan.digest !== planSubmission.current.digest
          || restoredPlan.provider !== planSubmission.current.provider || restoredPlan.requestedModel !== planSubmission.current.requestedModel)) throw new Error('조사 접수 정보가 다릅니다.')
        setPlan(restoredPlan); setPlanPending(!!planSubmission.current && !restoredPlan); setHistoryPlanUnknown(false)
        if (restoredPlan && planSubmission.current) removePlanSubmission(planScope, restoredPlan.id)
        const saved = readChatSubmission({ workspaceId, bundleId: current.bundle.id, bundleDigest: current.bundle.digest })
        if (saved) {
          const reconciled = detail.jobs.find(value => value.id === saved.submission.requestId && value.bundleId === saved.bundleId
            && value.approvalId === saved.submission.approvalId && value.provider === saved.provider
            && (saved.requestedModel === null || value.requestedModel === saved.requestedModel))
          setJob(reconciled ?? null)
          if (reconciled) removeChatSubmission(saved)
          else setSubmission(saved.submission)
        }
        setCanAbandonAnswer(!current.job && !saved)
      } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
      finally { if (!controller.signal.aborted) { locked.current = false; setBusy(false) } }
    })()
    return () => controller.abort()
  }, [base, selectedId, workspaceId])
  useEffect(() => () => pending.current?.abort(), [])
  async function requestAnswer(value: Bundle, controller: AbortController) {
    let submissionStarted = false
    let storageChecked = false
    setCanAbandonAnswer(false)
    try {
      const scope = { workspaceId, bundleId: value.id, bundleDigest: value.digest }
      let saved = readChatSubmission(scope)
      storageChecked = true
      submissionStarted = !!saved
      if (saved) {
        setSubmission(saved.submission)
        if (saved.provider !== connection!.provider || saved.requestedModel !== (connection?.model ?? null)) {
          throw new Error('확인하지 못한 답변 접수가 있습니다. 이전 제공자와 모델로 돌아가 접수 상태를 확인해 주세요.')
        }
      }
      const result = await sendChatAnswer(workspaceId, value.id, value.digest, connection!.provider, connection?.model ?? null,
        controller.signal, next => {
          const record = { ...scope, provider: connection!.provider, requestedModel: connection?.model ?? null, submission: next }
          saveChatSubmission(record)
          saved = record; submissionStarted = true; setSubmission(next)
        }, saved?.submission)
      if (saved) removeChatSubmission(saved)
      setSubmission(null)
      return result
    } catch (reason) {
      if (!controller.signal.aborted) setCanAbandonAnswer(storageChecked && !submissionStarted)
      throw reason
    }
  }

  async function abandonAnswer() {
    if (!canAbandonAnswer || locked.current || busy || submission || job || !pendingAnswer) return
    locked.current = true
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError('')
    try {
      if (readChatSubmission({ workspaceId, bundleId: bundle!.id, bundleDigest: bundle!.digest })) {
        throw new Error('확인하지 못한 답변 접수가 남아 있습니다. 접수 상태를 먼저 확인해 주세요.')
      }
      const detail = await request<{ id: string; bundles: Bundle[]; jobs: (AnswerJob & { workspaceId: string })[] }>(`${base}/chat-threads/${encodeURIComponent(threadId.current!)}`, { signal: controller.signal })
      if (controller.signal.aborted) return
      if (detail.id !== threadId.current || !detail.bundles.some(value => value.id === bundle!.id)
        || detail.bundles.some(value => value.workspaceId !== workspaceId)
        || detail.jobs.some(value => value.workspaceId !== workspaceId)) throw new Error('질문의 서재가 다릅니다.')
      const active = detail.jobs.find(value => value.bundleId === bundle!.id && activeAnswerJob(value))
      if (active) setJob(active)
      if (detail.jobs.some(activeAnswerJob)) throw new Error('현재 답변이 끝난 뒤 새 대화를 시작해 주세요.')
      setQuestion(current => current || bundle!.payload.question)
      setBundle(null); setPreviousTurns([]); setFresh(false); setSentQuestion(''); setCanAbandonAnswer(false)
      threadId.current = null; turnRequest.current = null; created.current = null
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) { locked.current = false; setBusy(false) } }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (locked.current || investigating || activePlan || planPending || pendingAnswer || activeAnswerJob(job) || attachments.uploading || !connected || !searchReady || !question.trim()) return
    if (connection?.provider !== 'CODEX' && attachments.items.some(item => item.mediaType.startsWith('image/'))) {
      setError('이미지 질문은 Codex를 선택해 주세요.'); return
    }
    locked.current = true
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError(''); setSentQuestion(question.trim())
    try {
      if (clarification) {
        const current = await request<ConversationScope>(`${base}/investigation-plans/jobs/${encodeURIComponent(clarification.jobId)}/clarification`, { signal: controller.signal })
        if (controller.signal.aborted) return
        if (current.workspaceId !== workspaceId || current.threadId !== threadId.current || current.parentBundleId !== bundle?.id
          || current.scopeId !== clarification.scopeId || current.revision !== clarification.revision
          || current.executionAllowed !== false || current.sessionOnly !== true || !Number.isFinite(Date.parse(current.expiresAt)) || Date.parse(current.expiresAt) <= Date.now()) {
          throw new Error('조회 범위가 변경되었습니다. 대화 내용을 보존했으니 범위를 다시 확인해 주세요.')
        }
      }
      const input = { threadId: threadId.current, parentBundleId: bundle?.id ?? null, question: question.trim(),
        ...(attachments.items.length ? { attachmentIds: attachments.items.map(item => item.id) } : {}) }
      const key = JSON.stringify(input)
      if (turnRequest.current?.key !== key) turnRequest.current = { key, requestId: crypto.randomUUID() }
      const value = await request<Bundle>(`${base}/chat-threads`, { method: 'POST', signal: controller.signal,
        body: JSON.stringify({ requestId: turnRequest.current.requestId, ...input }) })
      if (controller.signal.aborted) return
      if (value.workspaceId !== workspaceId) throw new Error('질문의 서재가 다릅니다.')
      if (bundle) setPreviousTurns(turns => [...turns, { bundle, job, fresh }])
      setJob(null); setSubmission(null); setCanAbandonAnswer(false); setFresh(true); setSentQuestion('')
      turnRequest.current = null
      threadId.current = value.payload.chatContext?.threadId ?? value.id
      attachments.reset(true); setBundle(value); setQuestion(''); created.current = selectedId === threadId.current ? null : threadId.current; onCreated(threadId.current)
      setPlan(null); setClarification(null); planSubmission.current = undefined
      await planAndAnswer(value, controller)
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) { locked.current = false; setBusy(false); setSentQuestion('') } }
  }

  async function planAndAnswer(value: Bundle, controller: AbortController) {
    if (value.payload.items.some(item => item.kind === 'ATTACHMENT_IMAGE')) {
      setPlanPending(false)
      setJob(await requestAnswer(value, controller))
      return
    }
    setPlanPending(true)
    const planScope = { workspaceId, bundleId: value.id, bundleDigest: value.digest }
    const decision = await sendConversationPlan(workspaceId, value.id, connection!.provider, connection?.model ?? null,
      controller.signal, next => { savePlanSubmission(planScope, next); planSubmission.current = next }, planSubmission.current)
    if (controller.signal.aborted) return
    if (planSubmission.current) removePlanSubmission(planScope, decision.id)
    setPlanPending(false)
    if (decision.status === 'SUCCEEDED' && ['PROPOSE_MYSQL_READ', 'PROPOSE_DATADOG_LOGS'].includes(decision.decision ?? '')) {
      setPlan(decision)
      return
    }
    if (!['SUCCEEDED', 'STOPPED'].includes(decision.status)) { setPlan(decision); throw new Error('자료 확인을 마치지 못했습니다. 조사를 자동으로 반복하지 않습니다.') }
    if (decision.decision === 'SEARCH' && decision.toolStatus === 'OK' && !decision.truncated) {
      setPlan(decision)
      if (captureRequest.current?.jobId !== decision.id) captureRequest.current = { jobId: decision.id, requestId: crypto.randomUUID() }
      const captured = await request<Bundle>(`${base}/investigation-plans/jobs/${encodeURIComponent(decision.id)}/capture`, {
        method: 'POST', signal: controller.signal, body: JSON.stringify({ requestId: captureRequest.current.requestId }),
      })
      if (controller.signal.aborted) return
      if (captured.workspaceId !== workspaceId || captured.payload.chatContext?.threadId !== threadId.current) throw new Error('조사 대상 대화가 다릅니다.')
      setPreviousTurns(turns => [...turns, { bundle: value, job: null, fresh: true }])
      setBundle(captured); onCreated(threadId.current!)
      value = captured
    } else if (decision.decision !== 'STOP' && decision.status !== 'STOPPED') {
      setPlan(decision)
      return
    }
    setPlan(null)
    if (value.payload.items.length && value.payload.status !== 'NO_EVIDENCE'
      && value.payload.items.every(item => ['ORIGINAL', 'ATTACHMENT_IMAGE', 'ATTACHMENT_TEXT'].includes(item.kind))) setJob(await requestAnswer(value, controller))
  }

  async function retryPlan() {
    if (!bundle || locked.current || !connected || !searchReady) return
    locked.current = true
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError('')
    try {
      if (historyPlanUnknown) {
        const restoredPlan = await readConversationPlan(workspaceId, bundle.id, controller.signal)
        if (!controller.signal.aborted) {
          const saved = planSubmission.current
          if (saved && restoredPlan && (restoredPlan.id !== saved.requestId || restoredPlan.provider !== saved.provider
            || restoredPlan.requestedModel !== saved.requestedModel || restoredPlan.digest !== saved.digest)) throw new Error('조사 접수 정보가 다릅니다.')
          setPlan(restoredPlan); setPlanPending(!!saved && !restoredPlan); setHistoryPlanUnknown(false)
          if (saved && restoredPlan) removePlanSubmission({ workspaceId, bundleId: bundle.id, bundleDigest: bundle.digest }, restoredPlan.id)
        }
      } else await planAndAnswer(bundle, controller)
    }
    catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) { locked.current = false; setBusy(false) } }
  }

  async function retryAnswer() {
    if (!bundle || locked.current || !connected || bundle.payload.items.some(item => !['ORIGINAL', 'ATTACHMENT_IMAGE', 'ATTACHMENT_TEXT'].includes(item.kind))) return
    locked.current = true
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError(''); setFresh(true)
    try {
      setJob(await requestAnswer(bundle, controller))
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) { locked.current = false; setBusy(false) } }
  }

  async function answerInvestigation(planId: string) {
    if (!bundle || locked.current || busy || !connected || !searchReady || activeAnswerJob(job)) return
    locked.current = true
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError('')
    if (captureRequest.current?.jobId !== planId) captureRequest.current = { jobId: planId, requestId: crypto.randomUUID() }
    try {
      const value = await request<Bundle>(`${base}/investigation-plans/jobs/${encodeURIComponent(planId)}/capture`, {
        method: 'POST', signal: controller.signal, body: JSON.stringify({ requestId: captureRequest.current.requestId }),
      })
      if (controller.signal.aborted) return
      if (value.workspaceId !== workspaceId || value.payload.chatContext?.threadId !== threadId.current) throw new Error('조사 대상 대화가 다릅니다.')
      setPreviousTurns(turns => [...turns, { bundle, job, fresh }])
      setBundle(value); setJob(null); setSubmission(null); setCanAbandonAnswer(false); setFresh(true); setPlan(null)
      onCreated(threadId.current!)
      if (value.payload.items.length && value.payload.status !== 'NO_EVIDENCE') setJob(await requestAnswer(value, controller))
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) { locked.current = false; setBusy(false) } }
  }

  async function answerOperational(receipt: ConversationCapture, signal: AbortSignal) {
    if (!bundle || locked.current || busy || !connected || !searchReady || activeAnswerJob(job) || signal.aborted) throw new Error('현재 대화가 끝난 뒤 다시 확인해 주세요.')
    if (receipt.threadId !== threadId.current || receipt.parentBundleId !== bundle.id) throw new Error('조회 대상 대화가 다릅니다.')
    locked.current = true
    const controller = new AbortController()
    const abort = () => controller.abort()
    signal.addEventListener('abort', abort, { once: true })
    let rejectAbort: () => void = () => {}
    const interrupted = new Promise<never>((_, reject) => {
      rejectAbort = () => reject(new Error('자료 연결 응답을 확인하지 못했습니다. 저장된 자료에서 다시 확인해 주세요.'))
      controller.signal.addEventListener('abort', rejectAbort, { once: true })
    })
    pending.current = controller
    setBusy(true); setError('')
    const input = { threadId: threadId.current, parentBundleId: bundle.id, question: bundle.payload.question, attachmentIds: [receipt.attachment.id] }
    const key = JSON.stringify(input)
    if (operationalTurn.current?.key !== key) operationalTurn.current = { key, requestId: crypto.randomUUID() }
    try {
      const current = await Promise.race([request<{ id: string; bundles: Bundle[] }>(`${base}/chat-threads/${encodeURIComponent(receipt.threadId)}`, { signal: controller.signal }), interrupted])
      if (controller.signal.aborted) return
      if (current.id !== receipt.threadId || current.bundles.at(-1)?.id !== receipt.parentBundleId
        || current.bundles.some(item => item.workspaceId !== workspaceId)) throw new Error('조회 이후 대화가 변경되었습니다. 저장한 자료는 자동 전송하지 않습니다.')
      const value = await Promise.race([request<Bundle>(`${base}/chat-threads`, { method: 'POST', signal: controller.signal,
        body: JSON.stringify({ requestId: operationalTurn.current.requestId, ...input }) }), interrupted])
      if (controller.signal.aborted) return
      if (value.workspaceId !== workspaceId || value.payload.chatContext?.threadId !== threadId.current) throw new Error('조회 대상 대화가 다릅니다.')
      signal.removeEventListener('abort', abort)
      setPreviousTurns(turns => [...turns, { bundle, job, fresh }])
      setBundle(value); setJob(null); setSubmission(null); setCanAbandonAnswer(false); setFresh(true); setInvestigating(false); setPlan(null)
      onCreated(threadId.current!)
      if (value.payload.items.length && value.payload.status !== 'NO_EVIDENCE') setJob(await Promise.race([requestAnswer(value, controller), interrupted]))
    } catch (reason) {
      if (!controller.signal.aborted) { setError((reason as Error).message); throw reason }
    } finally {
      signal.removeEventListener('abort', abort)
      controller.signal.removeEventListener('abort', rejectAbort)
      if (pending.current === controller) { locked.current = false; setBusy(false) }
    }
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
      {hasOmittedContext(bundle.payload.chatContext?.extractive) && <small>이전 대화 일부만 참고</small>}
      {current && busy && !sentQuestion && <p role="status" className="chat-thinking">확인해보겠습니다<span aria-hidden="true">…</span></p>}
      {job ? <AnswerJobsPanel key={job.id} workspaceId={workspaceId} bundleId={bundle.id} approval={null} initialJob={job} onJobChange={current ? setJob : undefined} compact animate={turnFresh} references={references} />
        : current && bundle.payload.items.some(item => !['ORIGINAL', 'ATTACHMENT_IMAGE', 'ATTACHMENT_TEXT'].includes(item.kind)) ? <p>이전 지식 문서가 포함된 질문입니다. 원본에서 다시 확인해 주세요.<Button variant="outline" onClick={() => { setQuestion(bundle.payload.question); setBundle(null); threadId.current = null }}>원본으로 다시 질문</Button></p>
        : current && !busy && answerPlanResolved && !planPending && bundle.payload.items.length > 0 && bundle.payload.status !== 'NO_EVIDENCE' && <Button disabled={!connected} onClick={() => void retryAnswer()}>{submission ? '접수 상태 다시 확인' : '답변 받기'}</Button>}
      {current && !busy && planPending && <Button disabled={!connected || !searchReady} onClick={() => void retryPlan()}>조사 접수 상태 확인</Button>}
      {current && !busy && pendingAnswer && canAbandonAnswer && !submission && !job && <Button variant="outline" onClick={() => void abandonAnswer()}>새 대화로 다시 질문</Button>}
      {!bundle.payload.items.length && <p>질문과 관련된 내용을 찾지 못했어요.</p>}
      {current && !planPending && (plan || (job && !activeAnswerJob(job)) || (!job && !bundle.payload.items.length)) && <InvestigationPanel key={`${workspaceId}:${bundle.id}`}
        workspaceId={workspaceId} bundleId={bundle.id} threadId={threadId.current ?? undefined} connection={connection} disabled={busy || !searchReady}
        initialJob={plan} onPlanChange={value => setPlan(current => current ? { ...current, ...value } : current)}
        onScope={setClarification} onAnswer={answerInvestigation} onCaptured={answerOperational} onBusyChange={setInvestigating} />}
      {current && !busy && (!job || !activeAnswerJob(job)) && <BrowserInvestigationPanel key={`browser:${workspaceId}:${bundle.id}`}
        workspaceId={workspaceId} bundleId={bundle.id} disabled={busy || investigating || activePlan || planPending} />}

    </div>})}
    {busy && sentQuestion && <div className="chat-thread"><h2 className="question-bubble">{sentQuestion}</h2><p role="status" className="chat-thinking">확인해보겠습니다<span aria-hidden="true">…</span></p></div>}
    <form className="chat-composer" onSubmit={event => void submit(event)} onDragOver={event => { if (event.dataTransfer.types.includes('Files')) event.preventDefault() }} onDrop={event => {
      if (!event.dataTransfer.files.length) return
      event.preventDefault(); if (!busy) void attachments.add(Array.from(event.dataTransfer.files))
    }}>
      <label className="sr-only" htmlFor="chat-question">질문</label>
      {clarification && clarification.questions.length > 0 && <p>앞선 질문에 이어서 확인합니다.</p>}
      {!searchReady && connected && <p role="status" className="chat-thinking">{searchState === 'ERROR' ? '검색 준비에 실패했습니다. 앱을 다시 실행해 주세요.' : '자료 검색을 준비하고 있어요…'}</p>}
      <textarea id="chat-question" rows={3} maxLength={256} value={question} disabled={!connected || !searchReady || busy || investigating || activePlan}
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
      <div className="composer-actions"><Button variant="ghost" type="button" size="icon" aria-label="파일 첨부" title="이미지·문서 첨부 · 최대 4개, 각각 5 MiB" disabled={busy || investigating || attachments.uploading || attachments.items.length >= 4} onClick={() => fileInput.current?.click()}><Plus /></Button><Button type="submit" size="icon" aria-label="질문 보내기" disabled={!connected || !searchReady || busy || investigating || activePlan || planPending || pendingAnswer || activeAnswerJob(job) || attachments.uploading || !question.trim()}><ArrowUp /></Button></div>
    </form>
  </section>
}
