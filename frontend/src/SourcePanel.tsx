import { lazy, Suspense, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { FolderInput, Link, Plus, RefreshCw, Search, Trash2 } from 'lucide-react'
import { Button } from './components/ui/button'
import { request } from './api'
import PublishedSearchPreview from './PublishedSearchPreview'
import EvidenceBundlePanel from './EvidenceBundlePanel'
import { hasFolderPicker, selectFolder } from './folderPicker'
import type { RelationSeed } from './RelationPanel'
const RelationPanel = lazy(() => import('./RelationPanel'))
const AnswerHistoryPanel = lazy(() => import('./AnswerHistoryPanel'))

export type Source = {
  id: string; name: string; kind: string; location: string; domain: string; status: string
  revision: string | null; error: string | null; syncedAt: string | null; rootEntryId: string | null
  pollingEnabled: boolean; pollIntervalSeconds: number
  nextPollAt: number | null; consecutiveFailures: number; pollingRevision: number
}
type Hit = { entryId: string | null; name: string; sourceId: string; domain: string; path: string; snippet: string; score: number; startLine: number; endLine: number; bodyMatch: boolean; kind: 'ORIGINAL' | 'KNOWLEDGE'; cardId: string | null; version: number | null; freshness: string }
type Props = { workspaceId: string; revision: number; onChanged: () => void; onSelect: (id: string) => void; manageOnly?: boolean; onUpload?: () => void; renderLibrary?: (sources: Source[], rows: ReactNode) => ReactNode }
const states: Record<string, string> = { NEW: '수집 전', SYNCING: '수집 중', READY: '반영 완료', ERROR: '오류 · 이전 자료 유지' }
const sourceKinds: Record<string, string> = { LOCAL_FOLDER: '로컬 폴더', URL: '웹페이지', GITHUB: 'GitHub' }

export default function SourcePanel({ workspaceId, revision, onChanged, onSelect, manageOnly = false, onUpload, renderLibrary }: Props) {
  const [sources, setSources] = useState<Source[]>([])
  const [formOpen, setFormOpen] = useState(false)
  const [kind, setKind] = useState(onUpload ? 'FILE' : 'LOCAL_FOLDER')
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
  const [related, setRelated] = useState<RelationSeed | null>(null)
  const searchAbort = useRef<AbortController | null>(null)
  const mutationAbort = useRef<AbortController | null>(null)
  const pickerGeneration = useRef(0)
  const [picking, setPicking] = useState(false)
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

  useEffect(() => () => { pickerGeneration.current++; searchAbort.current?.abort(); mutationAbort.current?.abort() }, [])

  async function chooseFolder() {
    const generation = ++pickerGeneration.current
    setPicking(true); setError('')
    try {
      const selected = await selectFolder()
      if (generation !== pickerGeneration.current || !selected) return
      setLocation(selected)
      setName(previous => previous || selected.split('/').filter(Boolean).at(-1) || '새 폴더')
    } catch (reason) { if (generation === pickerGeneration.current) setError((reason as Error).message) }
    finally { if (generation === pickerGeneration.current) setPicking(false) }
  }

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
    if (kind === 'FILE' || picking || busy || !location.trim()) return
    void mutate('create', async () => {
      await request(`${base}/sources`, {
        method: 'POST', body: JSON.stringify({ name, kind, location, domain }),
        signal: mutationAbort.current?.signal,
      })
      setFormOpen(false)
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

  async function syncAll() {
    if (busy || sources.some(source => source.status === 'SYNCING')) return
    const failures: string[] = []
    await mutate('sync-all', async () => {
      const signal = mutationAbort.current!.signal
      for (const source of sources) {
        if (signal.aborted) return
        try { await request(`${base}/sources/${source.id}/sync`, { method: 'POST', signal }) }
        catch (reason) {
          if (signal.aborted) return
          failures.push(`${source.name}: ${(reason as Error).message}`)
        }
      }
    })
    if (!mutationAbort.current?.signal.aborted && failures.length) setError(failures.join(' · '))
  }

  const sourceRows = <ul className="source-list compact-source-list">{sources.map(source => <li key={source.id}>
    <div className="source-info">
      <button className="source-open" disabled={!source.rootEntryId || !!busy} onClick={() => source.rootEntryId && onSelect(source.rootEntryId)}><Link size={18} /><strong>{source.name}</strong></button>
      <span>{sourceKinds[source.kind] || source.kind} · {states[source.status] || source.status}</span>
      <details><summary>상세 정보</summary><small>{source.location}</small>
        {source.syncedAt && <small>최근 동기화 {new Date(source.syncedAt).toLocaleString('ko-KR')}</small>}
        {source.revision && <small>반영 버전 {source.revision.slice(0, 12)}</small>}
      </details>
      {source.error && <span role="status" className="modal-error">{source.error}</span>}
    </div>
    <div className="source-actions">
      <Button variant="ghost" size="icon" aria-label={`${source.name} Source 삭제`} disabled={!!busy || source.status === 'SYNCING'} onClick={() => setDeleting(source.id)}><Trash2 /></Button>
    </div>
    {deleting === source.id && <div className="source-confirm" role="alert"><span>삭제하시겠습니까?</span><Button variant="ghost" size="sm" disabled={!!busy} onClick={() => setDeleting(null)}>취소</Button><Button variant="destructive" size="sm" disabled={!!busy} onClick={() => void mutate(source.id, () => request(`${base}/sources/${source.id}`, { method: 'DELETE', signal: mutationAbort.current?.signal }))}>삭제</Button></div>}
  </li>)}</ul>

  const availableDomains = [...new Set(sources.map(source => source.domain).filter(Boolean))]
  return <section className="source-panel" aria-label="자료 연결과 검색">
    <div className="source-title">{!manageOnly && <h2><Link size={17} /> 자료 검색</h2>}<div className="source-actions"><Button size="icon" variant="ghost" aria-label="동기화" title={busy === 'sync-all' ? '동기화 중…' : '동기화'} aria-busy={busy === 'sync-all'} disabled={!!busy || !sources.length || sources.some(source => source.status === 'SYNCING')} onClick={() => void syncAll()}><RefreshCw /></Button><Button size="icon" variant="ghost" aria-label="자료 추가" title="자료 추가" aria-expanded={formOpen} disabled={!!busy || picking} onClick={() => setFormOpen(!formOpen)}><Plus /></Button></div></div>
    {error && <p role="alert" className="modal-error">{error}</p>}
    {formOpen && <form onSubmit={register} className="source-form">
      <label>종류<select aria-label="Source 종류" disabled={picking || !!busy} value={kind} onChange={event => { setKind(event.target.value); setLocation('') }}>
        {onUpload && <option value="FILE">파일</option>}<option value="LOCAL_FOLDER">폴더</option><option value="URL">웹 링크</option><option value="GITHUB">GitHub</option>
      </select></label>
      {kind === 'FILE' ? <div className="source-location folder-picker"><Button type="button" variant="outline" onClick={onUpload}>파일 선택</Button><small>선택한 파일의 복사본을 보관합니다. 변경 내용을 계속 반영하려면 폴더를 선택하세요.</small></div> : <>
      <label>이름<input required maxLength={120} aria-label="Source 이름" value={name} onChange={event => setName(event.target.value)} placeholder="예: 실험 기록" /></label>
      {kind === 'LOCAL_FOLDER' ? <div className="source-location folder-picker"><span>폴더</span><Button type="button" variant="outline" disabled={picking || !!busy || !hasFolderPicker()} onClick={() => void chooseFolder()}><FolderInput />{picking ? '선택 중…' : '폴더 선택'}</Button><output aria-label="선택한 폴더">{location || '선택한 폴더 없음'}</output><small>{hasFolderPicker() ? '폴더 안의 파일을 동기화합니다. 개별 파일은 종류에서 파일을 선택하세요.' : '폴더 선택은 설치한 Dougle 앱에서 지원합니다.'}</small></div> : <label className="source-location">위치<input required maxLength={2048} aria-label="Source 위치" value={location} onChange={event => setLocation(event.target.value)} placeholder={kind === 'GITHUB' ? 'https://github.com/owner/repository' : 'https://example.com/document'} /></label>}
      <label>분류 (선택)<input maxLength={80} aria-label="Source 도메인" value={domain} onChange={event => setDomain(event.target.value)} placeholder="예: 프로젝트 이름" /></label>
      <Button type="submit" size="sm" disabled={!!busy || picking || !location.trim()}>등록</Button>
      </>}
    </form>}
    {renderLibrary ? renderLibrary(sources, sourceRows) : sourceRows}
    {!manageOnly && <><form className="search-form" onSubmit={event => void search(event)}>
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
    </>}
    {related && <Suspense fallback={<p role="status">관계 화면을 여는 중…</p>}><RelationPanel workspaceId={workspaceId} seed={related} domains={domains} onClose={() => setRelated(null)} onOriginal={onSelect} onKnowledge={(cardId, version) => setPublished({ cardId, version })} /></Suspense>}
    {published && <PublishedSearchPreview workspaceId={workspaceId} cardId={published.cardId} version={published.version} onClose={() => setPublished(null)} />}
    {bundleOpen && <EvidenceBundlePanel workspaceId={workspaceId} question={query} domains={domains} includeKnowledge={includeKnowledge} onClose={() => setBundleOpen(false)} />}
    {historyOpen && <Suspense fallback={<p role="status">답변 이력 화면을 여는 중…</p>}><AnswerHistoryPanel workspaceId={workspaceId} onClose={() => setHistoryOpen(false)} /></Suspense>}
  </section>
}
