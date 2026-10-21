import { useEffect, useRef, useState, type FormEvent } from 'react'
import { request, type EvidenceLocation } from './api'
import { Button } from './components/ui/button'
import GenerationPanel from './GenerationPanel'
import GenerationJobsPanel from './GenerationJobsPanel'
import PublicationPanel from './PublicationPanel'
import EvidenceOriginButton from './EvidenceOriginButton'
import KnowledgeOrganization from './KnowledgeOrganization'
import KnowledgeMarkdown from './KnowledgeMarkdown'

type Card = { id: string; workspaceId: string; title: string; latestVersion: number; createdAt: string }
type Evidence = {
  snapshotId: string; name: string; path: string; sourceRevision: string; contentHash: string
  sourceKind: string | null; sourceLocation: string | null
  startLine: number; endLine: number; quote: string; freshness: 'CURRENT' | 'STALE' | 'MISSING'
}
type Document = {
  card: Card
  version: { number: number; title: string; markdown: string; status: string; manuallyEdited: boolean; evidence: Evidence[] }
}
const knowledgePath = (workspaceId: string) => `/workspaces/${encodeURIComponent(workspaceId)}/knowledge`
const freshness = { CURRENT: '보관된 최신 원본과 일치', STALE: '원본 또는 추출 내용 변경됨', MISSING: '원본 삭제됨 · 근거 복사본 보존' }

export function DraftForm({ workspaceId, entryId, text }: { workspaceId: string; entryId: string; text: string }) {
  const [open, setOpen] = useState(false)
  const [title, setTitle] = useState('')
  const [markdown, setMarkdown] = useState('')
  const [startLine, setStartLine] = useState(1)
  const [endLine, setEndLine] = useState(1)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)
  const pending = useRef<AbortController | null>(null)
  useEffect(() => () => pending.current?.abort(), [])
  const lines = text.split('\n')

  async function save(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    const controller = new AbortController()
    pending.current = controller
    setBusy(true)
    setError('')
    setSaved(false)
    try {
      await request(knowledgePath(workspaceId), { method: 'POST', signal: controller.signal,
        body: JSON.stringify({ title, markdown, citations: [{ entryId, startLine, endLine }] }) })
      if (!controller.signal.aborted) { setSaved(true); setOpen(false); setTitle(''); setMarkdown('') }
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }

  return <section className="draft-section" aria-label="근거 연결 초안">
    <Button variant="outline" size="sm" disabled={busy} onClick={() => { setOpen(!open); setSaved(false) }}>근거로 지식 초안 작성</Button>
    {saved && <p role="status">수동 초안을 저장했습니다. 왼쪽 지식 문서에서 확인하세요.</p>}
    {error && <p role="alert">{error}</p>}
    {open && <form className="knowledge-form" onSubmit={event => void save(event)}>
      <p>AI 생성·검증이 아닌 수동 초안입니다. 근거 원본 복사본이 별도로 보존됩니다.</p>
      <label>지식 제목<input required maxLength={120} value={title} disabled={busy} onChange={event => setTitle(event.target.value)} /></label>
      <label>Markdown 초안<textarea required maxLength={100000} rows={6} value={markdown} disabled={busy} onChange={event => setMarkdown(event.target.value)} /></label>
      <p>추출 텍스트 기준 행 번호입니다. 원본 파일의 행/페이지와 다를 수 있습니다. 최대 500행.</p>
      <label>근거 시작 행<input type="number" required min={1} max={lines.length} value={startLine} disabled={busy} onChange={event => setStartLine(Number(event.target.value))} /></label>
      <label>근거 끝 행<input type="number" required min={startLine} max={Math.min(lines.length, startLine + 499)} value={endLine} disabled={busy} onChange={event => setEndLine(Number(event.target.value))} /></label>
      <pre aria-label="선택한 근거">{lines.slice(Math.max(0, startLine - 1), Math.min(endLine, startLine + 499)).join('\n')}</pre>
      <Button type="submit" disabled={busy}>{busy ? '저장 중…' : '미검증 초안 저장'}</Button>
    </form>}
  </section>
}

export default function KnowledgePanel({ workspaceId, onOpenOriginal }: {
  workspaceId: string; onOpenOriginal?: (location: EvidenceLocation) => void
}) {
  const [cards, setCards] = useState<Card[]>([])
  const [visibleIds, setVisibleIds] = useState<string[] | null>(null)
  const [selected, setSelected] = useState('')
  const [number, setNumber] = useState<number | null>(null)
  const [document, setDocument] = useState<Document | null>(null)
  const [title, setTitle] = useState('')
  const [markdown, setMarkdown] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [editing, setEditing] = useState(false)
  const [revision, setRevision] = useState(0)
  const pending = useRef<AbortController | null>(null)
  const base = knowledgePath(workspaceId)
  useEffect(() => () => pending.current?.abort(), [])
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    request<Card[]>(base, { signal: controller.signal }).then(value => { if (!controller.signal.aborted) setCards(value) })
      .catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [base, revision])
  useEffect(() => {
    const controller = new AbortController()
    setDocument(null)
    setError('')
    setEditing(false)
    setConfirmDelete(false)
    if (selected) {
      request<Document>(`${base}/${selected}${number === null ? '' : `/versions/${number}`}`, { signal: controller.signal })
        .then(value => {
          if (!controller.signal.aborted) { setDocument(value); setTitle(value.version.title); setMarkdown(value.version.markdown) }
        }).catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
    }
    return () => controller.abort()
  }, [base, selected, number, revision])

  async function mutate(remove: boolean) {
    if (!document || busy) return
    const controller = new AbortController()
    pending.current = controller
    setBusy(true)
    setError('')
    try {
      await request(`${base}/${selected}${remove ? '' : '/versions'}`, {
        method: remove ? 'DELETE' : 'POST', signal: controller.signal,
        body: remove ? undefined : JSON.stringify({ expectedVersion: document.card.latestVersion, title, markdown }),
      })
      if (!controller.signal.aborted) {
        if (remove) setSelected('')
        setNumber(null)
        setRevision(value => value + 1)
      }
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }

  return <section className="knowledge-panel" aria-label="지식 초안 보관함">
    <p>근거 연결 초안, 승인 기반 AI 생성, 사람 검토 후 명시적 게시를 지원합니다. 자동 사실 검증은 제공하지 않습니다.</p>
    <GenerationJobsPanel workspaceId={workspaceId} revision={revision} openDisabled={editing || busy} onOpen={id => {
      if (!editing && !busy) { setSelected(id); setNumber(null); setRevision(value => value + 1) }
    }} />
    <Button variant="outline" size="sm" disabled={busy || editing} onClick={() => setRevision(value => value + 1)}>지식 새로고침</Button>
    <KnowledgeOrganization workspaceId={workspaceId} cards={cards} selected={selected} revision={revision}
      disabled={busy || editing || confirmDelete} onFilter={setVisibleIds} onBusy={setBusy} />
    {error && <p role="alert" className="error-banner">{error}</p>}
    {!loading && visibleIds !== null && !cards.some(card => visibleIds.includes(card.id)) && <p>현재 지식 폴더에 카드가 없습니다.</p>}
    {loading ? <p role="status">지식 목록을 불러오는 중…</p> : cards.length === 0 ? <div className="empty-state">
      <h2>아직 저장된 지식 초안이 없습니다</h2><p>원본 파일을 선택하고 분석 영역의 “근거로 지식 초안 작성”을 이용하세요.</p>
    </div> : <ul className="source-list">{cards.filter(card => visibleIds === null || visibleIds.includes(card.id)).map(card => <li key={card.id}>
      <Button variant="ghost" disabled={busy || editing} onClick={() => { setSelected(card.id); setNumber(null) }}>{card.title} · v{card.latestVersion}</Button>
    </li>)}</ul>}
    {selected && !document && !error && <p role="status">지식을 불러오는 중…</p>}
    {document && <article className="knowledge-document">
      <h2>{document.version.title}</h2>
      <p>v{document.version.number} / 최신 v{document.card.latestVersion} · 생성 상태 {document.version.status} · {document.version.manuallyEdited ? '사람이 작성·편집한 초안' : 'AI 생성 초안'} · 검토/게시 상태는 아래 별도 표시</p>
      <div className="source-actions">
        <Button variant="outline" size="sm" disabled={busy || editing || document.version.number <= 1} onClick={() => setNumber(document.version.number - 1)}>이전 버전</Button>
        <Button variant="outline" size="sm" disabled={busy || editing || document.version.number >= document.card.latestVersion} onClick={() => setNumber(document.version.number + 1)}>다음 버전</Button>
        <Button variant="outline" size="sm" disabled={busy || editing || document.version.number !== document.card.latestVersion} onClick={() => setEditing(true)}>초안 편집</Button>
        <Button variant="outline" size="sm" disabled={busy || editing} onClick={() => setConfirmDelete(true)}>지식 삭제</Button>
      </div>
      {confirmDelete && <div className="source-confirm" role="group" aria-label="지식 삭제 확인">
        모든 지식 버전과 단독 참조 근거 복사본을 삭제합니다. 원본 자료는 유지됩니다.
        <Button variant="destructive" disabled={busy} onClick={() => void mutate(true)}>삭제 확인</Button>
        <Button variant="outline" disabled={busy} onClick={() => setConfirmDelete(false)}>취소</Button>
      </div>}
      {editing ? <form className="knowledge-form" onSubmit={event => { event.preventDefault(); void mutate(false) }}>
        <label>지식 제목<input required maxLength={120} disabled={busy} value={title} onChange={event => setTitle(event.target.value)} /></label>
        <label>Markdown 초안<textarea required maxLength={100000} rows={10} disabled={busy} value={markdown} onChange={event => setMarkdown(event.target.value)} /></label>
        <p>이전 버전과 근거 복사본을 유지하고 새로운 미검증 버전을 저장합니다.</p>
        <div className="source-actions"><Button disabled={busy} type="submit">새 버전 저장</Button>
          <Button type="button" variant="outline" disabled={busy} onClick={() => { setEditing(false); setTitle(document.version.title); setMarkdown(document.version.markdown) }}>편집 취소</Button></div>
      </form> : <KnowledgeMarkdown key={`markdown-${selected}-${document.version.number}`} markdown={document.version.markdown} />}
      {!editing && document.version.number === document.card.latestVersion && <GenerationPanel
        key={`${selected}-${document.version.number}`} base={`${base}/${selected}`} disabled={busy || confirmDelete}
        onBusy={setBusy} onQueued={() => { setBusy(false); setRevision(value => value + 1) }} />}
      {!editing && <PublicationPanel key={`publication-${selected}-${document.version.number}-${revision}`}
        base={`${base}/${selected}`} version={document.version.number} disabled={busy || confirmDelete}
        onBusy={setBusy} onOpen={setNumber} />}
      <h3>보존된 근거</h3><p>최신성은 마지막으로 수집한 로컬 복사본과 비교합니다. 외부 원본의 실시간 상태나 사실 검증 결과가 아닙니다.</p>
      {document.version.evidence.map((item, index) => <section className="evidence-item" key={`${item.snapshotId}-${index}`}>
        <strong>[E{index + 1}] {item.path} · 추출 텍스트 {item.startLine}~{item.endLine}행</strong>
        {item.sourceLocation && <p>{item.sourceKind} · {item.sourceLocation}</p>}
        <p>{freshness[item.freshness]}</p><small>revision {item.sourceRevision}<br />SHA-256 {item.contentHash}</small>
        <pre>{item.quote}</pre>
        {onOpenOriginal && <EvidenceOriginButton key={`${document.version.number}-${item.snapshotId}`}
          path={`${base}/${selected}/versions/${document.version.number}/evidence/${item.snapshotId}/location`}
          disabled={busy || editing || confirmDelete} onBusy={setBusy} onOpen={onOpenOriginal} />}
        <p>현재 원본은 인용 당시 복사본과 다를 수 있습니다. 아래 다운로드는 당시 원본입니다.</p>
        <a href={`/api${base}/${selected}/evidence/${item.snapshotId}/content`} download>당시 근거 원본 다운로드</a>
      </section>)}
    </article>}
  </section>
}
