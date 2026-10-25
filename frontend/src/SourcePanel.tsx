import { lazy, Suspense, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { ArrowRight, Database, FileText, FolderInput, Github, Link, Minus, Plus, RefreshCw, Search, Trash2 } from 'lucide-react'
import { Button } from './components/ui/button'
import { request } from './api'
import PublishedSearchPreview from './PublishedSearchPreview'
import EvidenceBundlePanel from './EvidenceBundlePanel'
import { hasFolderPicker, selectFolder } from './folderPicker'
import { sourceName } from './sourceName'
import DeleteConfirmation from './DeleteConfirmation'
import MaterialWarning from './MaterialWarning'
import SourceSyncProgress from './SourceSyncProgress'
import { readMaterialSync, recordMaterialSync } from './materialSync'
import { pluginForUrl, type PluginId } from './plugins'
import type { RelationSeed } from './RelationPanel'
const RelationPanel = lazy(() => import('./RelationPanel'))
const AnswerHistoryPanel = lazy(() => import('./AnswerHistoryPanel'))

export type Source = {
  id: string; name: string; kind: string; location: string; domain: string; status: string
  revision: string | null; error: string | null; syncedAt: string | null; rootEntryId: string | null
  createdAt: string | null
  pollingEnabled: boolean; pollIntervalSeconds: number
  nextPollAt: number | null; consecutiveFailures: number; pollingRevision: number
}
type Hit = { entryId: string | null; name: string; sourceId: string; domain: string; path: string; snippet: string; score: number; startLine: number; endLine: number; bodyMatch: boolean; kind: 'ORIGINAL' | 'KNOWLEDGE'; cardId: string | null; version: number | null; freshness: string }
type Props = { workspaceId: string; revision: number; onChanged: () => void; onSelect: (id: string) => void; onPluginConnect?: (id: PluginId) => void; onBusyChange?: (busy: boolean) => void; manageOnly?: boolean; onUpload?: () => void; uploading?: boolean; syncUploads?: boolean; hasMaterials?: boolean; analysisFailures?: Record<string, string>; renderLibrary?: (sources: Source[], rows: ReactNode) => ReactNode }
const states: Record<string, string> = { NEW: '수집 전', SYNCING: '수집 중', READY: '검색 가능', PARTIAL: '일부 처리', ERROR: '오류 · 이전 자료 유지' }
const sourceKinds: Record<string, string> = { LOCAL_FOLDER: '로컬 폴더', URL: '웹페이지', GITHUB: 'GitHub', MYSQL: 'MySQL' }
const sourceIcons: Record<string, typeof Link> = { LOCAL_FOLDER: FolderInput, URL: Link, GITHUB: Github, MYSQL: Database }

export default function SourcePanel({ workspaceId, revision, onChanged, onSelect, onBusyChange, onPluginConnect, manageOnly = false, onUpload, uploading = false, syncUploads = false, hasMaterials = false, analysisFailures = {}, renderLibrary }: Props) {
  const [sources, setSources] = useState<Source[]>([])
  const [formOpen, setFormOpen] = useState(false)
  const [kind, setKind] = useState(onUpload ? 'FILE' : 'LOCAL_FOLDER')
  const [location, setLocation] = useState('')
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
  const [lastSync, setLastSync] = useState(() => readMaterialSync(workspaceId))
  const [syncStep, setSyncStep] = useState('')
  const [syncLog, setSyncLog] = useState<string[]>([])
  const [syncError, setSyncError] = useState('')
  const [sourceErrors, setSourceErrors] = useState<Record<string, string>>({})
  const [activeSource, setActiveSource] = useState<string | null>(null)
  const stopSync = useRef(false)
  const base = `/workspaces/${workspaceId}`
  const latestSources = useRef(sources)
  const latestChanged = useRef(onChanged)
  useEffect(() => { latestSources.current = sources; latestChanged.current = onChanged }, [sources, onChanged])
  const watching = sources.some(source => source.pollingEnabled || source.status === 'SYNCING')
  const canSync = sources.length > 0 || (syncUploads && hasMaterials)
  const collecting = sources.some(source => source.status === 'SYNCING')
  const syncing = busy === 'sync-all' || busy === 'create' || uploading || collecting
  const syncedAt = [lastSync, ...sources.map(source => source.syncedAt)].filter((value): value is string => !!value && Number.isFinite(Date.parse(value)))
    .sort((first, second) => Date.parse(second) - Date.parse(first))[0]

  useEffect(() => { setLastSync(readMaterialSync(workspaceId)) }, [workspaceId, revision])

  useEffect(() => { onBusyChange?.(!!busy || picking || collecting) }, [busy, picking, collecting, onBusyChange])
  useEffect(() => () => onBusyChange?.(false), [onBusyChange])

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
      await createSource('LOCAL_FOLDER', selected)
    } catch (reason) { if (generation === pickerGeneration.current) setError((reason as Error).message) }
    finally { if (generation === pickerGeneration.current) setPicking(false) }
  }

  async function mutate(id: string, action: () => Promise<unknown>) {
    onBusyChange?.(true)
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
    } catch (reason) {
      if (!signal.aborted) {
        if (id === 'sync-all') setSyncError((reason as Error).message)
        else setError((reason as Error).message)
      }
      return false
    }
    finally { if (!signal.aborted) setBusy('') }
  }

  function register(event: FormEvent) {
    event.preventDefault()
    if (kind === 'FILE' || picking || busy || !location.trim()) return
    void createSource(kind, location)
  }

  async function createSource(sourceKind: string, sourceLocation: string) {
    let name: string
    try { name = sourceName(sourceKind, sourceLocation) }
    catch { setError('올바른 주소를 입력해 주세요.'); return }
    setSyncStep(`${name} · 자료 추가·동기화 중…`)
    setSyncLog([])
    setSyncError('')
    await mutate('create', async () => {
      const signal = mutationAbort.current!.signal
      const source = await request<Source>(`${base}/sources`, {
        method: 'POST', body: JSON.stringify({ name, kind: sourceKind, location: sourceLocation.trim(), domain: '' }),
        signal,
      })
      if (signal.aborted) return
      setActiveSource(source.id)
      let synced: Source
      try { synced = await request<Source>(`${base}/sources/${source.id}/sync`, { method: 'POST', signal }) }
      finally { setActiveSource(null) }
      setLocation('')
      if (synced.status === 'READY') setLastSync(recordMaterialSync(workspaceId))
      setSyncStep('자료 목록 갱신 중…')
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
    if (!canSync || busy || uploading || sources.some(source => source.status === 'SYNCING')) return
    const failures: string[] = []
    stopSync.current = false
    setSyncLog([])
    setSyncError('')
    setSourceErrors({})
    const completed = await mutate('sync-all', async () => {
      const signal = mutationAbort.current!.signal
      if (syncUploads) {
        setSyncStep('보관한 파일 · 분석·검색 색인 갱신 중…')
        try {
          const result = await request<{ processed: number; failures: { name: string; error: string }[] }>(`${base}/files/sync`, { method: 'POST', signal })
          if (signal.aborted) return
          failures.push(...result.failures.map(item => `${item.name}: ${item.error}`))
          if (result.failures.length) setSyncError(result.failures[0].error)
          setSyncLog(log => [...log, `보관한 파일 · ${result.processed}개 처리 · 실패 ${result.failures.length}개`].slice(-5))
        } catch (reason) {
          if (signal.aborted) return
          failures.push((reason as Error).message)
          setSyncError((reason as Error).message)
          setSyncLog(log => [...log, '실패 · 보관한 파일'].slice(-5))
        }
      }
      for (const source of sources) {
        if (signal.aborted || stopSync.current) return
        setActiveSource(source.id)
        setSyncStep(`${source.name} · 자료 수집·분석 중…`)
        try {
          const synced = await request<Source>(`${base}/sources/${source.id}/sync`, { method: 'POST', signal })
          if (signal.aborted) return
          if (synced.status === 'ERROR') {
            failures.push(`${source.name}: ${synced.error || '동기화하지 못했습니다.'}`)
            setSourceErrors(errors => ({ ...errors, [source.id]: synced.error || '동기화하지 못했습니다.' }))
          }
          setSyncLog(log => [...log, `${synced.status === 'ERROR' ? '실패' : '완료'} · ${source.name}`].slice(-5))
        }
        catch (reason) {
          if (signal.aborted) return
          failures.push(`${source.name}: ${(reason as Error).message}`)
          setSourceErrors(errors => ({ ...errors, [source.id]: (reason as Error).message }))
          setSyncLog(log => [...log, `실패 · ${source.name}`].slice(-5))
        }
        finally { setActiveSource(null) }
      }
      setSyncStep('자료 목록 갱신 중…')
    })
    if (completed && !stopSync.current && failures.length === 0) setLastSync(recordMaterialSync(workspaceId))
  }

  const sourceRows = <ul className="source-list compact-source-list">{sources.map(source => {
    const SourceIcon = sourceIcons[source.kind] || Link
    const analysisError = source.status === 'PARTIAL' ? source.error : source.rootEntryId ? analysisFailures[source.rootEntryId] : undefined
    const failed = source.status === 'ERROR' || !!sourceErrors[source.id]
    const requiresConnection = /연결이 필요|원격 서버 HTTP 오류: (401|403)/.test(sourceErrors[source.id] || source.error || '')
    const requiredPlugin = failed && requiresConnection ? source.kind === 'MYSQL' ? 'MYSQL' : source.kind === 'URL' ? pluginForUrl(source.location) : null : null
    return <li key={source.id}>
    <div className="source-info">
      <div className={`material-name${failed || analysisError ? ' has-warning' : ''}`}><button className="source-open" disabled={!source.rootEntryId || !!busy} onClick={() => source.rootEntryId && onSelect(source.rootEntryId)}><SourceIcon size={18} aria-hidden="true" /><strong>{source.name}</strong></button>{(failed || analysisError) && <MaterialWarning name={source.name} error={requiredPlugin === 'NOTION' ? 'Notion 연결이 필요합니다.' : failed ? sourceErrors[source.id] || source.error : analysisError} label={requiredPlugin ? '연결 필요' : failed ? '동기화 실패' : '분석 실패'} onAction={requiredPlugin && onPluginConnect && !syncing && !busy && !picking ? () => onPluginConnect(requiredPlugin) : undefined} />}</div>
      <span>{sourceKinds[source.kind] || source.kind} · {requiresConnection ? '연결 필요' : analysisError && !failed ? '일부 처리' : states[failed ? 'ERROR' : source.status] || source.status}</span>
      <details><summary>상세 정보</summary><small>{source.location}</small>
        {source.syncedAt && <small>최근 동기화 {new Date(source.syncedAt).toLocaleString('ko-KR')}</small>}
        {source.revision && <small>반영 버전 {source.revision.slice(0, 12)}</small>}
      </details>
    </div>
    <span className="file-meta">—</span>
    <time className="file-meta source-added" dateTime={source.createdAt || undefined} title={source.createdAt ? new Date(source.createdAt).toLocaleString('ko-KR') : '이전 버전에서 등록 날짜를 기록하지 않았습니다.'}>{source.createdAt ? new Date(source.createdAt).toLocaleDateString('ko-KR') : '—'}</time>
    <div className="source-actions">
      <Button variant="ghost" size="icon" aria-label={`${source.name} Source 삭제`} disabled={!!busy || source.status === 'SYNCING'} onClick={() => { setError(''); setDeleting(source.id) }}><Trash2 /></Button>
    </div>
  </li>})}</ul>

  const availableDomains = [...new Set(sources.map(source => source.domain).filter(Boolean))]
  return <section className={`source-panel${manageOnly ? ' source-library' : ''}`} aria-label="자료 연결과 검색">
<div className="source-title">{!manageOnly && <h2><Link size={17} /> 자료 검색</h2>}<div className="source-actions">{syncError && <span className="material-name"><MaterialWarning name="파일 동기화" error={syncError} /></span>}{syncedAt && <time className="source-last-sync" dateTime={syncedAt} title={new Date(syncedAt).toLocaleString('ko-KR')}>최근 동기화 {new Date(syncedAt).toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</time>}<Button size="icon" variant="ghost" aria-label="동기화" title={syncing ? '동기화 중…' : '동기화'} aria-busy={syncing} disabled={!!busy || picking || uploading || !canSync || collecting} onClick={() => void syncAll()}><RefreshCw className={syncing ? 'sync-spinner' : undefined} /></Button><Button size="icon" variant="ghost" aria-label={formOpen ? '자료 추가 닫기' : '자료 추가'} title={formOpen ? '자료 추가 닫기' : '자료 추가'} aria-expanded={formOpen} disabled={!!busy || picking || uploading || collecting} onClick={() => setFormOpen(!formOpen)}>{formOpen ? <Minus /> : <Plus />}</Button></div></div>
    {(activeSource || collecting) && <SourceSyncProgress key={activeSource || sources.find(source => source.status === 'SYNCING')!.id} workspaceId={workspaceId} sourceId={activeSource || sources.find(source => source.status === 'SYNCING')!.id} onCancel={() => { stopSync.current = true }} />}
    {(busy === 'sync-all' || busy === 'create') && <section className="sync-progress" aria-label="동기화 진행 상황">
      {!activeSource && <div className="upload-progress" role="status"><span className="upload-spinner" aria-hidden="true" /><span className="upload-progress-name" title={syncStep}>{syncStep}</span><span className="upload-progress-track" aria-hidden="true" /></div>}
      {syncLog.length > 0 && <ul aria-label="동기화 처리 내역">{syncLog.map((line, index) => <li key={`${index}-${line}`} title={line}>{line}</li>)}</ul>}
    </section>}
    {error && !deleting && <p role="alert" className="modal-error">{error}</p>}
    {deleting && <DeleteConfirmation name={sources.find(source => source.id === deleting)?.name ?? ''} busy={!!busy} error={error} onCancel={() => { setDeleting(null); setError('') }} onConfirm={() => void mutate(deleting, () => request(`${base}/sources/${deleting}`, { method: 'DELETE', signal: mutationAbort.current?.signal }))} />}
    {formOpen && <form onSubmit={register} className="source-form">
      <div className="source-kind-options" role="group" aria-label="자료 종류">
        {[
          ...(onUpload ? [{ value: 'FILE', label: '파일', icon: FileText }] : []),
          { value: 'LOCAL_FOLDER', label: '폴더', icon: FolderInput },
          { value: 'URL', label: '웹 링크', icon: Link },
          { value: 'GITHUB', label: 'GitHub', icon: Github },
        ].map(({ value, label, icon: Icon }) => <button key={value} type="button" aria-pressed={kind === value} disabled={picking || !!busy || uploading} onClick={() => { setKind(value); setLocation(''); setError('') }}><Icon size={17} aria-hidden="true" />{label}</button>)}
      </div>
      <div className="source-add-target">
        {kind === 'FILE' || kind === 'LOCAL_FOLDER' ? <Button type="button" variant="outline" className="source-target-control source-picker-button"
          disabled={uploading || picking || !!busy || (kind === 'LOCAL_FOLDER' && !hasFolderPicker())}
          aria-busy={uploading || picking || busy === 'create'}
          title={kind === 'LOCAL_FOLDER' && !hasFolderPicker() ? '설치한 앱에서 선택할 수 있습니다' : undefined}
          onClick={kind === 'FILE' ? onUpload : () => void chooseFolder()}>
          {uploading || busy === 'create' ? <span className="upload-spinner" aria-hidden="true" /> : kind === 'FILE' ? <FileText aria-hidden="true" /> : <FolderInput aria-hidden="true" />}
          <span>{uploading || busy === 'create' ? '추가 중…' : picking ? '선택 중…' : kind === 'FILE' ? '파일 선택' : '폴더 선택'}</span>
          <ArrowRight className="source-target-arrow" aria-hidden="true" />
        </Button> : <div className="source-target-control source-address-target">
          {kind === 'GITHUB' ? <Github size={18} aria-hidden="true" /> : <Link size={18} aria-hidden="true" />}
          <input type="url" required maxLength={2048} aria-label={kind === 'GITHUB' ? 'GitHub 주소' : '웹 링크 주소'} disabled={!!busy || uploading} value={location} onChange={event => setLocation(event.target.value)} placeholder={kind === 'GITHUB' ? 'GitHub 주소 입력' : '웹 링크 입력'} />
          <Button type="submit" variant="ghost" size="icon" aria-label="추가" title="추가" aria-busy={busy === 'create'} disabled={!!busy || uploading || !location.trim()}>{busy === 'create' ? <span className="upload-spinner" aria-hidden="true" /> : <ArrowRight aria-hidden="true" />}</Button>
        </div>}
      </div>
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
