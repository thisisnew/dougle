import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'
import AnswerApprovalPanel from './AnswerApprovalPanel'

type Props = { workspaceId: string; conversationId: string; revision: number; turns: { jobId: string; position: number }[] }
type Bundle = { id: string; question: string; status: string }
type Preview = {
  conversationId: string; revision: number; provider: string; requestedModel: string; digest: string
  prompt: string; characters: number; maxCharacters: number
  bundle: { id: string; workspaceId: string; payload: { question: string; items: { label: string; title: string; quote: string
    supports: { label: string; name: string; quote: string }[] }[] } }
  history: { jobId: string; position: number; question: string; answer: string }[]
}

export default function ConversationPreviewPanel(props: Props) {
  return <ScopedPreview key={`${props.workspaceId}:${props.conversationId}:${props.revision}`} {...props} />
}

function ScopedPreview({ workspaceId, conversationId, revision, turns }: Props) {
  const [bundles, setBundles] = useState<Bundle[]>([])
  const [bundleId, setBundleId] = useState('')
  const [jobIds, setJobIds] = useState<string[]>([])
  const [provider, setProvider] = useState('CODEX')
  const [model, setModel] = useState('')
  const [preview, setPreview] = useState<Preview | null>(null)
  const [busy, setBusy] = useState(true)
  const [error, setError] = useState('')
  const [savedId, setSavedId] = useState<string | null>(null)
  const pending = useRef<AbortController | null>(null)
  const base = `/workspaces/${encodeURIComponent(workspaceId)}`

  useEffect(() => {
    const controller = new AbortController()
    pending.current = controller
    request<Bundle[]>(`${base}/evidence-bundles`, { signal: controller.signal })
      .then(values => { if (!controller.signal.aborted) setBundles(values) })
      .catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
      .finally(() => { if (!controller.signal.aborted) setBusy(false) })
    return () => pending.current?.abort()
  }, [base])

  function changed() { setPreview(null); setError(''); setSavedId(null) }
  async function freeze() {
    if (busy || !preview || savedId) return
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError('')
    try {
      const value = await request<{ id: string; workspaceId: string; bundleId: string; digest: string }>(`${base}/conversations/${encodeURIComponent(conversationId)}/requests`, {
        method: 'POST', signal: controller.signal,
        body: JSON.stringify({ selection: { bundleId, expectedRevision: revision, jobIds, provider: preview.provider, requestedModel: preview.requestedModel }, digest: preview.digest }),
      })
      if (controller.signal.aborted) return
      if (value.workspaceId !== workspaceId || value.bundleId !== bundleId || value.digest !== preview.digest) throw new Error('고정한 문맥 범위가 다릅니다.')
      setSavedId(value.id)
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }
  async function prepare() {
    if (busy || !bundleId || jobIds.length === 0) return
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); changed()
    try {
      const value = await request<Preview>(`${base}/conversations/${encodeURIComponent(conversationId)}/preview`, {
        method: 'POST', signal: controller.signal,
        body: JSON.stringify({ bundleId, expectedRevision: revision, jobIds, provider, requestedModel: model }),
      })
      if (controller.signal.aborted) return
      if (value.conversationId !== conversationId || value.revision !== revision || value.bundle.workspaceId !== workspaceId
        || value.bundle.id !== bundleId || value.provider !== provider || value.history.length !== jobIds.length
        || new Set(value.history.map(item => item.jobId)).size !== jobIds.length
        || value.history.some(item => !jobIds.includes(item.jobId))) throw new Error('미리보기 범위가 다릅니다.')
      setPreview(value)
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }

  return <section aria-label="이전 문맥 미리보기">
    <h4>이전 문맥 미리보기 · 전송 안 함</h4>
    <p>먼저 자료 검색에서 새 질문의 근거 묶음을 저장하세요. 이전 답변은 자동 선택하지 않습니다.</p>
    <label>새 질문의 근거 묶음<select aria-label="새 질문의 근거 묶음" disabled={busy || !!savedId} value={bundleId} onChange={event => { changed(); setBundleId(event.target.value) }}>
      <option value="">선택하세요</option>{bundles.map(bundle => <option key={bundle.id} value={bundle.id} disabled={bundle.status !== 'READY'}>{bundle.question} · {bundle.id.slice(0, 8)}</option>)}
    </select></label>
    <fieldset disabled={busy || !!savedId}><legend>포함할 이전 답변 · 최대 5개</legend>
      {turns.map(turn => <label key={turn.jobId}><input type="checkbox" checked={jobIds.includes(turn.jobId)}
        disabled={jobIds.length >= 5 && !jobIds.includes(turn.jobId)} onChange={event => {
          changed(); setJobIds(values => event.target.checked ? [...values, turn.jobId] : values.filter(id => id !== turn.jobId))
        }} />{turn.position}번 답변 · {turn.jobId.slice(0, 8)}</label>)}
    </fieldset>
    <label>문맥 제공자<select aria-label="문맥 제공자" value={provider} disabled={busy || !!savedId} onChange={event => { changed(); setProvider(event.target.value) }}>
      <option value="CODEX">Codex</option><option value="CLAUDE">Claude</option>
    </select></label>
    <label>문맥 요청 모델<input aria-label="문맥 요청 모델" maxLength={128} value={model} disabled={busy || !!savedId} onChange={event => { changed(); setModel(event.target.value) }} /></label>
    <p>빈 모델은 CLI 기본값입니다. 미리보기만으로 저장·승인·전송하지 않습니다. 요청 내용을 확인한 뒤 고정 저장 → 승인 → 별도 전송 순서로 실행합니다.</p>
    <Button type="button" disabled={busy || !!savedId || !bundleId || jobIds.length === 0} onClick={() => void prepare()}>선택한 문맥 미리보기</Button>
    {busy && <p role="status">문맥 확인 중…</p>}
    {error && <p role="alert">{error} 보관함이나 근거가 바뀌었다면 새로고침하세요.</p>}
    {preview && <section aria-label="문맥 미리보기 결과">
      <h5>현재 질문의 근거 · {preview.bundle.payload.question}</h5>
      {preview.bundle.payload.items.map(item => <div key={item.label}><p>[{item.label}] {item.title}</p><pre>{item.quote}</pre>
        {item.supports.map(support => <details key={support.label}><summary>[{item.label}/{support.label}] {support.name}</summary><pre>{support.quote}</pre></details>)}
      </div>)}
      <h5>이전 질문·답변 · 사실 근거가 아님</h5>
      {preview.history.map(item => <div key={item.jobId}><p>{item.position}번 · {item.question}</p><pre>{item.answer}</pre></div>)}
      <p>{preview.provider} · {preview.requestedModel || 'CLI 기본값'} · {preview.characters}/{preview.maxCharacters}자 (토큰 수 아님)</p>
      <details><summary>요청 전문과 해시</summary><pre>{preview.prompt}</pre><pre>{preview.digest}</pre></details>
      <p>고정 저장하면 이 묶음의 문맥·제공자·모델을 변경할 수 없습니다. 변경하려면 새 묶음을 만드세요. 닫아도 서버에 도착한 저장은 남습니다.</p>
      <Button type="button" disabled={busy || !!savedId} onClick={() => void freeze()}>이 문맥 고정 저장 (전송 안 함)</Button>
      {savedId && <div><p role="status">문맥을 고정했습니다. 아직 외부로 전송하지 않았습니다.</p>
        <AnswerApprovalPanel key={savedId} workspaceId={workspaceId} bundleId={bundleId} preview={preview} />
      </div>}
    </section>}
  </section>
}
