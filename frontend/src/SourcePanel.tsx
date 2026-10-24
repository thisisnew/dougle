import { lazy, Suspense, useEffect, useRef, useState, type FormEvent } from 'react'
import { FolderInput, Link, RefreshCw, Search, Trash2 } from 'lucide-react'
import { Button } from './components/ui/button'
import { request } from './api'
import PublishedSearchPreview from './PublishedSearchPreview'
import EvidenceBundlePanel from './EvidenceBundlePanel'
import SourcePollingSettings from './SourcePollingSettings'
import SourceMetadataEditor from './SourceMetadataEditor'
import type { RelationSeed } from './RelationPanel'
const RelationPanel = lazy(() => import('./RelationPanel'))
const AnswerHistoryPanel = lazy(() => import('./AnswerHistoryPanel'))

type Source = {
  id: string; name: string; kind: string; location: string; domain: string; status: string
  revision: string | null; error: string | null; syncedAt: string | null; rootEntryId: string | null
  pollingEnabled: boolean; pollIntervalSeconds: number
  nextPollAt: number | null; consecutiveFailures: number; pollingRevision: number
}
type Hit = { entryId: string | null; name: string; sourceId: string; domain: string; path: string; snippet: string; score: number; startLine: number; endLine: number; bodyMatch: boolean; kind: 'ORIGINAL' | 'KNOWLEDGE'; cardId: string | null; version: number | null; freshness: string }
type Props = { workspaceId: string; revision: number; onChanged: () => void; onSelect: (id: string) => void }
const states: Record<string, string> = { NEW: '수집 전', SYNCING: '수집 중', READY: '반영 완료', ERROR: '오류 · 이전 자료 유지' }
const sourceKinds: Record<string, string> = { LOCAL_FOLDER: '로컬 폴더', URL: '웹페이지', GITHUB: 'GitHub' }

export default function SourcePanel({ workspaceId, revision, onChanged, onSelect }: Props) {
  const [sources, setSources] = useState<Source[]>([])
  const [formOpen, setFormOpen] = useState(false)
  const [managing, setManaging] = useState(false)
  const [kind, setKind] = useState('LOCAL_FOLDER')
  const [name, setName] = useState('')
  const [location, setLocation] = useState('')
  const [domain, setDomain] = useState('')
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const [query, setQuery] = useState('')
  const [domains, setDomains] = useState<string[]>([])
  const [hits, setHits] = useState<Hit[] | null>(null)
  const [searching, setSearching] = useState(false)
  const [bundleOpen, setBundleOpen] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [includeKnowledge, setIncludeKnowledge] = useState(true)
  const [published, setPublished] = useState<{ cardId: string; version: number } | null>(null)
  const [deleting, setDeleting] = useState<string | null>(null)
  const [editing, setEditing] = useState<string | null>(null)
  const [related, setRelated] = useState<RelationSeed | null>(null)
  const searchAbort = useRef<AbortController | null>(null)
  const mutationAbort = useRef<AbortController | null>(null)
  const base = `/workspaces/${workspaceId}`
  const latestSources = useRef(sources)
  const latestChanged = useRef(onChanged)
  useEffect(() => { latestSources.current = sources; latestChanged.current = onChanged }, [sources, onChanged])
  const watching = sources.some(source => source.pollingEnabled || source.status === 'SYNCING')

  useEffect(() => {
    setDomains(previous => {
      const next = previous.filter(value => sources.some(source => source.domain === value))
      return next.length === previous.length ? previous : next
    })
  }, [sources])

  useEffect(() => {
    if (!watching) return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    async function refresh() {
      try {
        const next = await request<Source[]>(`${base}/sources`, { signal: controller.signal })
        if (controller.signal.aborted) return
        const changed = next.some(source => {
          const previous = latestSources.current.find(item => item.id === source.id)
          return previous && (previous.revision !== source.revision || previous.syncedAt !== source.syncedAt || previous.pollingRevision !== source.pollingRevision)
        })
        setSources(next)
        if (changed) { setHits(null); latestChanged.current() }
      } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
      finally { if (!controller.signal.aborted) timer = setTimeout(() => void refresh(), 3000) }
    }
    timer = setTimeout(() => void refresh(), 3000)
    return () => { controller.abort(); clearTimeout(timer) }
  }, [base, watching])

  useEffect(() => {
    const controller = new AbortController()
    request<Source[]>(`${base}/sources`, { signal: controller.signal }).then(setSources)
      .catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
    return () => controller.abort()
  }, [base, revision])

  useEffect(() => () => { searchAbort.current?.abort(); mutationAbort.current?.abort() }, [])

  async function mutate(id: string, action: () => Promise<unknown>) {
    setBusy(id)
    setError('')
    mutationAbort.current = new AbortController()
    const signal = mutationAbort.current.signal
    try {
      await action()
      if (signal.aborted) return false
      const next = await request<Source[]>(`${base}/sources`, { signal })
      if (signal.aborted) return false
      setSources(next)
      searchAbort.current?.abort()
      setSearching(false)
      setHits(null)
      onChanged()
      setDeleting(null)
      return true
    } catch (reason) { if (!signal.aborted) setError((reason as Error).message); return false }
    finally { if (!signal.aborted) setBusy('') }
  }

  function register(event: FormEvent) {
    event.preventDefault()
    void mutate('create', async () => {
      await request(`${base}/sources`, {
        method: 'POST', body: JSON.stringify({ name, kind, location, domain }),
        signal: mutationAbort.current?.signal,
      })
      setFormOpen(false)
      setManaging(true)
      setName('')
      setLocation('')
      setDomain('')
    })
  }

  async function search(event: FormEvent) {
    event.preventDefault()
    searchAbort.current?.abort()
    const controller = new AbortController()
    searchAbort.current = controller
    setSearching(true)
    setError('')
    setHits(null)
    const params = new URLSearchParams({ query, includeKnowledge: String(includeKnowledge) })
    domains.forEach(value => params.append('domains', value))
    try {
      const found = await request<Hit[]>(`${base}/search?${params}`, { signal: controller.signal })
      if (!controller.signal.aborted) setHits(found)
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) setSearching(false) }
  }

  const availableDomains = [...new Set(sources.map(source => source.domain).filter(Boolean))]
  return <section className="source-panel" aria-label="자료 연결과 검색">
    <div className="source-title"><h2><Link size={17} /> 자료 검색</h2><Button size="sm" variant="outline" disabled={!!busy} onClick={() => setFormOpen(!formOpen)}><FolderInput /> 자료 연결</Button></div>
    <p className="source-hint">폴더·웹페이지·GitHub를 연결해 검색하세요. 원본은 변경하지 않습니다.</p>
    {error && <p role="alert" className="modal-error">{error}</p>}
    {formOpen && <form onSubmit={register} className="source-form">
      <label>종류<select aria-label="Source 종류" value={kind} onChange={event => setKind(event.target.value)}>
        <option value="LOCAL_FOLDER">로컬 폴더</option><option value="URL">웹 URL</option><option value="GITHUB">GitHub 저장소</option>
      </select></label>
      <label>이름<input required maxLength={120} aria-label="Source 이름" value={name} onChange={event => setName(event.target.value)} placeholder="예: 실험 기록" /></label>
      <label className="source-location">위치<input required maxLength={2048} aria-label="Source 위치" value={location} onChange={event => setLocation(event.target.value)} placeholder={kind === 'LOCAL_FOLDER' ? '/절대/폴더/경로' : kind === 'GITHUB' ? 'https://github.com/owner/repository' : 'https://example.com/document'} /></label>
      <label>분류 (선택)<input maxLength={80} aria-label="Source 도메인" value={domain} onChange={event => setDomain(event.target.value)} placeholder="예: 프로젝트 이름" /></label>
      <Button type="submit" size="sm" disabled={!!busy}>등록</Button>
    </form>}
    {sources.length > 0 && <details className="secondary-tools" open={managing} onToggle={event => setManaging(event.currentTarget.open)}><summary>연결한 자료 관리 · {sources.length}개{sources.some(source => source.status === 'ERROR') ? ' · 오류 있음' : sources.some(source => source.status === 'SYNCING') ? ' · 수집 중' : sources.some(source => source.status === 'NEW') ? ' · 가져오기 필요' : ''}</summary>
    <ul className="source-list">{sources.map(source => <li key={source.id}>
      <div className="source-info"><strong>{source.name}</strong><span>{sourceKinds[source.kind] || source.kind} · {source.domain || '분류 없음'} · {states[source.status] || source.status}</span>
        <small title={source.location}>{source.location}</small>
        <small>{source.revision ? `반영 버전 ${source.revision.slice(0, 12)} · ${source.syncedAt ? new Date(source.syncedAt).toLocaleString('ko-KR') : ''}` : '아직 반영된 버전 없음'} · 자동 폴링 {source.pollingEnabled ? 'ON' : 'OFF'}</small>
        {source.error && <span role="status" className="modal-error">{source.error}</span>}
      </div>
      <div className="source-actions"><Button size="sm" variant="outline" disabled={!!busy || source.status === 'SYNCING'} onClick={() => void mutate(source.id, () => request(`${base}/sources/${source.id}/sync`, { method: 'POST', signal: mutationAbort.current?.signal }))}><RefreshCw />{busy === source.id ? '수집 중…' : '동기화'}</Button>
        <Button variant="outline" size="sm" aria-label={`${source.name} 이름과 분류 수정`} disabled={!!busy || source.status === 'SYNCING'} onClick={() => setEditing(source.id)}>수정</Button>
        <Button variant="ghost" size="icon" aria-label={`${source.name} Source 삭제`} disabled={!!busy} onClick={() => setDeleting(source.id)}><Trash2 /></Button>
      </div>
      <details className="source-settings"><summary>자동 갱신 설정</summary><p>기본은 꺼짐입니다. 앱이 실행 중일 때만 갱신하며, 꺼도 이미 시작한 수집은 완료됩니다. AI는 자동 호출하지 않습니다.</p><SourcePollingSettings source={source} busy={!!busy} onSave={settings => void mutate(source.id, () => request(`${base}/sources/${source.id}/polling`, { method: 'PATCH', body: JSON.stringify(settings), signal: mutationAbort.current?.signal }))} /></details>
      {editing === source.id && <SourceMetadataEditor source={source} busy={!!busy} onClose={() => setEditing(null)} onSave={metadata => mutate(source.id, () => request(`${base}/sources/${source.id}`, { method: 'PATCH', body: JSON.stringify(metadata), signal: mutationAbort.current?.signal }))} />}
      {deleting === source.id && <div className="source-confirm" role="alert"><span>보관한 자료도 삭제합니다. PC 원래 폴더·원격 저장소는 유지됩니다.</span><Button variant="ghost" size="sm" disabled={!!busy} onClick={() => setDeleting(null)}>취소</Button><Button variant="destructive" size="sm" disabled={!!busy} onClick={() => void mutate(source.id, () => request(`${base}/sources/${source.id}`, { method: 'DELETE', signal: mutationAbort.current?.signal }))}>Source 삭제 확인</Button></div>}
    </li>)}</ul></details>}
    <form className="search-form" onSubmit={event => void search(event)}>
      <label className="sr-only" htmlFor="knowledge-query">자료 검색</label>
      <input id="knowledge-query" required maxLength={256} value={query} onChange={event => setQuery(event.target.value)} placeholder="찾고 싶은 내용이나 질문을 입력하세요" />
      <Button type="submit" size="sm" disabled={searching || !query.trim()}><Search />{searching ? '검색 중…' : '검색'}</Button>
    </form>
    <div className="search-assistant-actions"><Button type="button" size="sm" variant="outline" onClick={() => setBundleOpen(true)}>AI에게 질문하기</Button><Button type="button" size="sm" variant="ghost" onClick={() => setHistoryOpen(true)}>이전 답변 보기</Button><p>AI는 선택한 자료를 확인하고 전송에 동의한 뒤 실행됩니다.</p></div>
    <details className="search-options"><summary>검색 범위{domains.length ? ` · 분류 ${domains.length}개 선택` : ' · 전체 분류'}{includeKnowledge ? '' : ' · 원본만'}</summary>
      <label className="check-option"><input type="checkbox" checked={includeKnowledge} onChange={event => { searchAbort.current?.abort(); setSearching(false); setIncludeKnowledge(event.target.checked); setHits(null) }} /><span>게시한 지식 문서도 포함<small>아직 게시하지 않은 초안은 검색하지 않습니다.</small></span></label>
      {availableDomains.length > 0 && <fieldset className="domain-options"><legend>분류 선택 · 선택하지 않으면 전체 검색</legend>{availableDomains.map(value => <label key={value}><input type="checkbox" checked={domains.includes(value)} onChange={event => setDomains(event.target.checked ? [...domains, value] : domains.filter(item => item !== value))} />{value}</label>)}</fieldset>}
    </details>
    {hits && <div className="search-results" aria-live="polite"><p>검색 결과 {hits.length}개 (최대 30개)</p>{hits.map(hit => <div className="search-hit" key={hit.cardId || hit.entryId}><button onClick={() => {
      if (hit.kind === 'KNOWLEDGE' && hit.cardId && hit.version) setPublished({ cardId: hit.cardId, version: hit.version })
      else if (hit.entryId) onSelect(hit.entryId)
    }}><strong>{hit.name}</strong><small>{hit.kind === 'KNOWLEDGE' ? `게시 지식 v${hit.version} · 근거 ${hit.freshness}` : '원본'} · {hit.domain || '분류 없음'} · {hit.path}</small><small>{hit.bodyMatch ? '본문 검색어 주변' : '이름·심볼 일치 · 본문 앞부분'}{hit.startLine > 0 ? ` · ${hit.kind === 'KNOWLEDGE' ? '게시 Markdown' : '추출 텍스트'} ${hit.startLine}–${hit.endLine}행 (일부)` : ' · 본문 없음'}</small><span>{hit.snippet}</span></button>
      <details className="secondary-tools"><summary>관련 자료 더 보기</summary><Button size="sm" variant="outline" aria-label={`${hit.name} 관련 자료`} onClick={() => {
        const id = hit.kind === 'KNOWLEDGE' ? hit.cardId : hit.entryId
        if (id) setRelated({ kind: hit.kind, id, version: hit.version, title: hit.name })
      }}>관련 자료</Button></details></div>)}</div>}
    {related && <Suspense fallback={<p role="status">관계 화면을 여는 중…</p>}><RelationPanel workspaceId={workspaceId} seed={related} domains={domains} onClose={() => setRelated(null)} onOriginal={onSelect} onKnowledge={(cardId, version) => setPublished({ cardId, version })} /></Suspense>}
    {published && <PublishedSearchPreview workspaceId={workspaceId} cardId={published.cardId} version={published.version} onClose={() => setPublished(null)} />}
    {bundleOpen && <EvidenceBundlePanel workspaceId={workspaceId} question={query} domains={domains} includeKnowledge={includeKnowledge} onClose={() => setBundleOpen(false)} />}
    {historyOpen && <Suspense fallback={<p role="status">답변 이력 화면을 여는 중…</p>}><AnswerHistoryPanel workspaceId={workspaceId} onClose={() => setHistoryOpen(false)} /></Suspense>}
  </section>
}
