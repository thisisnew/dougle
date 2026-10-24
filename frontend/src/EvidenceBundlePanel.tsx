import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'
import QuickAnswerPanel from './QuickAnswerPanel'

type Support = { label: string; name: string; startLine: number; endLine: number; quote: string }
type Item = { label: string; kind: string; title: string; version: number | null; path: string | null; sourceRevision: string | null; contentHash: string | null; textHash: string; startLine: number; endLine: number; quote: string; supports: Support[] }
type Bundle = { id: string; createdAt: string; digest: string; payload: { question: string; domains: string[]; includeKnowledge: boolean; status: string; usedCharacters: number; maxCharacters: number; items: Item[]; excluded: { kind: string; entryId: string | null; cardId: string | null; reason: string }[] } }
type Summary = { id: string; question: string; status: string; createdAt: string }
const reasons: Record<string, string> = { REMOVED: '자료가 삭제되었거나 조회 불가', SCOPE_CHANGED: '도메인 범위 변경', NO_BODY_MATCH: '이름·심볼만 일치', ITEM_LIMIT: '최대 5개 제한', INDEX_CHANGED: '색인과 현재 본문이 다름', NO_LONGER_PUBLISHED: '게시 버전 변경', NOT_CURRENT: '근거 변경·삭제', CHARACTER_LIMIT: '인용문 12,000자 제한', UNSUPPORTED: '지원하지 않는 자료' }

export default function EvidenceBundlePanel({ workspaceId, question: initialQuestion, domains, includeKnowledge, onClose }: {
  workspaceId: string; question: string; domains: string[]; includeKnowledge: boolean; onClose: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const pending = useRef<AbortController | null>(null)
  const [bundles, setBundles] = useState<Summary[]>([])
  const [bundle, setBundle] = useState<Bundle | null>(null)
  const [busy, setBusy] = useState(true)
  const [error, setError] = useState('')
  const [question, setQuestion] = useState(initialQuestion)
  const base = `/workspaces/${encodeURIComponent(workspaceId)}/evidence-bundles`
  useEffect(() => {
    const element = dialog.current!
    element.showModal()
    const controller = new AbortController()
    request<Summary[]>(base, { signal: controller.signal }).then(value => { if (!controller.signal.aborted) setBundles(value) })
      .catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
      .finally(() => { if (!controller.signal.aborted) setBusy(false) })
    return () => { controller.abort(); pending.current?.abort(); element.close() }
  }, [base])

  async function act(action: 'create' | 'open' | 'delete', id?: string) {
    if (busy) return
    if (action === 'delete' && !window.confirm('저장한 질문과 연결된 답변을 삭제할까요? 원본과 지식 문서는 유지됩니다.')) return
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError('')
    if (action === 'open') setBundle(null)
    try {
      if (action === 'delete') {
        await request(`${base}/${encodeURIComponent(id!)}`, { method: 'DELETE', signal: controller.signal })
        if (!controller.signal.aborted) setBundle(null)
      } else {
        const value = await request<Bundle>(action === 'create' ? base : `${base}/${encodeURIComponent(id!)}`, {
          method: action === 'create' ? 'POST' : 'GET', signal: controller.signal,
          ...(action === 'create' ? { body: JSON.stringify({ question, domains, includeKnowledge }) } : {}),
        })
        if (!controller.signal.aborted) setBundle(value)
      }
      if (!controller.signal.aborted) {
        const summaries = await request<Summary[]>(base, { signal: controller.signal })
        if (!controller.signal.aborted) setBundles(summaries)
      }
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }

  return <dialog ref={dialog} className="proposal-merge" aria-label="AI에게 질문하기" onCancel={event => { event.preventDefault(); onClose() }}>
    <div className="panel-heading"><h2>AI에게 질문하기</h2><Button variant="outline" onClick={onClose}>닫기</Button></div>
    <p>질문 작성 → 참고 자료 확인 → 전송 승인 순서로 진행합니다. 승인 전에는 자료를 보내지 않습니다.</p>
    <label className="field-label">질문<input maxLength={256} value={question} disabled={busy} onChange={event => setQuestion(event.target.value)} placeholder="내 자료에 대해 무엇이 궁금한가요?" /></label>
    <p>{domains.join(', ') || '전체 분류'} · {includeKnowledge ? '게시한 지식 포함' : '원본만'}</p>
    <Button disabled={busy || !question.trim()} onClick={() => void act('create')}>참고 자료 찾기</Button>
    {bundles.length > 0 && <details><summary>저장한 질문 불러오기</summary><label className="field-label">저장한 질문<select aria-label="저장한 질문" disabled={busy} value={bundle?.id || ''} onChange={event => { if (event.target.value) void act('open', event.target.value) }}>
      <option value="">이전 질문 불러오기</option>{bundles.map(value => <option key={value.id} value={value.id}>{value.question} · {new Date(value.createdAt).toLocaleString('ko-KR')}</option>)}
    </select></label></details>}
    {busy && <p role="status">처리 중… 닫아도 이미 요청된 저장은 완료될 수 있습니다.</p>}
    {error && <p role="alert">{error}</p>}
    {bundle && <section aria-label="저장된 근거 내용">
      <h3>{bundle.payload.question}</h3>
      <p role="status">{bundle.payload.status === 'NO_EVIDENCE' ? '사용 가능한 근거 없음 · 자료를 추가하거나 질문을 바꿔 주세요.' : '참고 자료를 찾았습니다. 질문에 맞는 내용인지 확인해 주세요.'}</p>
      {bundle.payload.items.map(item => <section key={item.label}>
        <h4>[{item.label}] {item.title} · {item.kind === 'KNOWLEDGE' ? `게시 v${item.version} Markdown` : '원본 추출 텍스트'} {item.startLine}–{item.endLine}행 (일부)</h4>
        <pre>{item.quote}</pre>
        <details><summary>원문 식별 정보</summary><p>경로: {item.path || '게시 지식'}</p><p>Source 버전: {item.sourceRevision || '해당 없음'}</p><pre>본문 SHA-256: {item.textHash}{'\n'}원본 SHA-256: {item.contentHash || '게시 버전으로 식별'}</pre></details>
        {item.supports.map(support => <details key={support.label}><summary>[{item.label}/{support.label}] {support.name} · {support.startLine}–{support.endLine}행</summary><pre>{support.quote}</pre></details>)}
      </section>)}
      {bundle.payload.excluded.length > 0 && <details><summary>제외한 검색 후보 {bundle.payload.excluded.length}개</summary><ul>{bundle.payload.excluded.map((value, index) => <li key={index}>{value.kind} · {value.entryId || value.cardId} · {reasons[value.reason] || value.reason}</li>)}</ul></details>}
      {bundle.payload.status !== 'NO_EVIDENCE' && bundle.payload.items.length > 0 && <QuickAnswerPanel key={bundle.id} workspaceId={workspaceId} bundleId={bundle.id} bundleDigest={bundle.digest} />}
      <details className="secondary-tools"><summary>질문 관리</summary>
        <p>당시 참고 자료와 질문을 이 PC에 보관합니다. 전송 전 현재 자료와 다시 대조합니다. 최대 5개 자료·12,000자, 서재당 질문 100개까지 보관합니다.</p>
        <p>{bundle.payload.domains.join(', ') || '전체 분류'} · {bundle.payload.usedCharacters}/{bundle.payload.maxCharacters}자</p>
        <Button variant="destructive" disabled={busy} onClick={() => void act('delete', bundle.id)}>질문과 답변 삭제</Button>
      </details>
    </section>}
  </dialog>
}
