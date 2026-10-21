import { useEffect, useRef, useState, type FormEvent } from 'react'
import { FolderInput, Link, RefreshCw, Search, Trash2 } from 'lucide-react'
import { Button } from './components/ui/button'
import { request } from './api'
import PublishedSearchPreview from './PublishedSearchPreview'
import EvidenceBundlePanel from './EvidenceBundlePanel'

type Source = {
  id: string; name: string; kind: string; location: string; domain: string; status: string
  revision: string | null; error: string | null; syncedAt: string | null; rootEntryId: string | null
  pollingEnabled: boolean; pollIntervalSeconds: number
}
type Hit = { entryId: string | null; name: string; sourceId: string; domain: string; path: string; snippet: string; score: number; startLine: number; endLine: number; bodyMatch: boolean; kind: 'ORIGINAL' | 'KNOWLEDGE'; cardId: string | null; version: number | null; freshness: string }
type Props = { workspaceId: string; revision: number; onChanged: () => void; onSelect: (id: string) => void }
const states: Record<string, string> = { NEW: '수집 전', SYNCING: '수집 중', READY: '반영 완료', ERROR: '오류 · 이전 자료 유지' }

export default function SourcePanel({ workspaceId, revision, onChanged, onSelect }: Props) {
  const [sources, setSources] = useState<Source[]>([])
  const [formOpen, setFormOpen] = useState(false)
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
  const [includeKnowledge, setIncludeKnowledge] = useState(true)
  const [published, setPublished] = useState<{ cardId: string; version: number } | null>(null)
  const [deleting, setDeleting] = useState<string | null>(null)
  const searchAbort = useRef<AbortController | null>(null)
  const mutationAbort = useRef<AbortController | null>(null)
  const base = `/workspaces/${workspaceId}`

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
      if (signal.aborted) return
      setSources(await request<Source[]>(`${base}/sources`, { signal }))
      setHits(null)
      onChanged()
      setDeleting(null)
    } catch (reason) { if (!signal.aborted) setError((reason as Error).message) }
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
    <div className="source-title"><h2><Link size={17} /> 자료 연결과 검색</h2><Button size="sm" variant="outline" disabled={!!busy} onClick={() => setFormOpen(!formOpen)}><FolderInput /> Source 등록</Button></div>
    <p className="source-hint">로컬 폴더·공개 HTTPS URL·공개 GitHub 저장소를 읽기 전용으로 수집합니다. 자동 폴링은 꺼져 있습니다.</p>
    {error && <p role="alert" className="modal-error">{error}</p>}
    {formOpen && <form onSubmit={register} className="source-form">
      <label>종류<select aria-label="Source 종류" value={kind} onChange={event => setKind(event.target.value)}>
        <option value="LOCAL_FOLDER">로컬 폴더</option><option value="URL">웹 URL</option><option value="GITHUB">GitHub 저장소</option>
      </select></label>
      <label>이름<input required maxLength={120} aria-label="Source 이름" value={name} onChange={event => setName(event.target.value)} placeholder="예: 실험 기록" /></label>
      <label className="source-location">위치<input required maxLength={2048} aria-label="Source 위치" value={location} onChange={event => setLocation(event.target.value)} placeholder={kind === 'LOCAL_FOLDER' ? '/절대/폴더/경로' : kind === 'GITHUB' ? 'https://github.com/owner/repository' : 'https://example.com/document'} /></label>
      <label>도메인 (선택)<input maxLength={80} aria-label="Source 도메인" value={domain} onChange={event => setDomain(event.target.value)} placeholder="사용자 지정 분류" /></label>
      <Button type="submit" size="sm" disabled={!!busy}>등록</Button>
    </form>}
    <ul className="source-list">{sources.map(source => <li key={source.id}>
      <div className="source-info"><strong>{source.name}</strong><span>{source.kind} · {source.domain || '분류 없음'} · {states[source.status] || source.status}</span>
        <small title={source.location}>{source.location}</small>
        <small>{source.revision ? `반영 버전 ${source.revision.slice(0, 12)} · ${source.syncedAt ? new Date(source.syncedAt).toLocaleString('ko-KR') : ''}` : '아직 반영된 버전 없음'} · 자동 폴링 OFF</small>
        {source.error && <span role="status" className="modal-error">{source.error}</span>}
      </div>
      <div className="source-actions"><Button size="sm" variant="outline" disabled={!!busy || source.status === 'SYNCING'} onClick={() => void mutate(source.id, () => request(`${base}/sources/${source.id}/sync`, { method: 'POST', signal: mutationAbort.current?.signal }))}><RefreshCw />{busy === source.id ? '수집 중…' : '동기화'}</Button>
        <Button variant="ghost" size="icon" aria-label={`${source.name} Source 삭제`} disabled={!!busy} onClick={() => setDeleting(source.id)}><Trash2 /></Button>
      </div>
      {deleting === source.id && <div className="source-confirm" role="alert"><span>보관한 자료도 삭제합니다. PC 원래 폴더·원격 저장소는 유지됩니다.</span><Button variant="ghost" size="sm" disabled={!!busy} onClick={() => setDeleting(null)}>취소</Button><Button variant="destructive" size="sm" disabled={!!busy} onClick={() => void mutate(source.id, () => request(`${base}/sources/${source.id}`, { method: 'DELETE', signal: mutationAbort.current?.signal }))}>Source 삭제 확인</Button></div>}
    </li>)}</ul>
    <form className="search-form" onSubmit={event => void search(event)}>
      <label className="sr-only" htmlFor="knowledge-query">자료 검색</label>
      <input id="knowledge-query" required maxLength={256} value={query} onChange={event => setQuery(event.target.value)} placeholder="원본·게시 지식·Java 심볼 검색" />
      <Button type="submit" size="sm" disabled={searching || !query.trim()}><Search />{searching ? '검색 중…' : '검색'}</Button>
      <Button type="button" size="sm" variant="outline" onClick={() => setBundleOpen(true)}>근거 묶음</Button>
    </form>
    <label><input type="checkbox" checked={includeKnowledge} onChange={event => { searchAbort.current?.abort(); setSearching(false); setIncludeKnowledge(event.target.checked); setHits(null) }} />게시 지식도 검색 (미게시 초안 제외)</label>
    {availableDomains.length > 0 && <fieldset className="domain-options"><legend>검색 도메인 · 선택하지 않으면 전체</legend>{availableDomains.map(value => <label key={value}><input type="checkbox" checked={domains.includes(value)} onChange={event => setDomains(event.target.checked ? [...domains, value] : domains.filter(item => item !== value))} />{value}</label>)}</fieldset>}
    {hits && <div className="search-results" aria-live="polite"><p>검색 결과 {hits.length}개 (최대 30개)</p>{hits.map(hit => <button key={hit.cardId || hit.entryId} onClick={() => {
      if (hit.kind === 'KNOWLEDGE' && hit.cardId && hit.version) setPublished({ cardId: hit.cardId, version: hit.version })
      else if (hit.entryId) onSelect(hit.entryId)
    }}><strong>{hit.name}</strong><small>{hit.kind === 'KNOWLEDGE' ? `게시 지식 v${hit.version} · 근거 ${hit.freshness}` : '원본'} · {hit.domain || '분류 없음'} · {hit.path}</small><small>{hit.bodyMatch ? '본문 검색어 주변' : '이름·심볼 일치 · 본문 앞부분'}{hit.startLine > 0 ? ` · ${hit.kind === 'KNOWLEDGE' ? '게시 Markdown' : '추출 텍스트'} ${hit.startLine}–${hit.endLine}행 (일부)` : ' · 본문 없음'}</small><span>{hit.snippet}</span></button>)}</div>}
    {published && <PublishedSearchPreview workspaceId={workspaceId} cardId={published.cardId} version={published.version} onClose={() => setPublished(null)} />}
    {bundleOpen && <EvidenceBundlePanel workspaceId={workspaceId} question={query} domains={domains} includeKnowledge={includeKnowledge} onClose={() => setBundleOpen(false)} />}
  </section>
}
