import { lazy, Suspense, useEffect, useRef, useState, type FormEvent } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { BookOpen, ChevronRight, Download, FileText, Folder, FolderOpen, FolderPlus, Layers3, MessageSquare, Plus, RefreshCw, ShieldCheck, Trash2, Upload, X } from 'lucide-react'
import { Button } from './components/ui/button'
import { entryPath, request, type Area, type Entry, type EvidenceLocation, type Preview, type Session } from './api'
import SourcePanel from './SourcePanel'
import { downloadApi } from './download'
import AnalysisPanel from './AnalysisPanel'
import KnowledgePanel from './KnowledgePanel'
import PixelLandscape from './PixelLandscape'
import { type ConnectionIndicator } from './connectionIndicator'
import ChatPanel, { QuestionHistory } from './ChatPanel'
import { preferredProvider } from './providerPreference'
import { loadModel, requestedModel } from './modelPreference'
const ProviderConnectionPanel = lazy(() => import('./ProviderConnectionPanel'))
const AnswerHistoryPanel = lazy(() => import('./AnswerHistoryPanel'))

type Modal = { kind: 'workspace' | 'folder' } | { kind: 'delete-workspace' } | { kind: 'delete-entry'; entry: Entry }
const emptySession: Session = { workspaces: [], activeWorkspaceId: null }
const formatSize = (size: number) => size < 1024 ? `${size} B` : size < 1024 ** 2 ? `${(size / 1024).toFixed(1)} KB` : `${(size / 1024 ** 2).toFixed(1)} MB`

export default function App() {
  const [session, setSession] = useState<Session>(emptySession)
  const [ready, setReady] = useState(false)
  const [area, setArea] = useState<Area>('ORIGINAL')
  const [view, setView] = useState<'CHAT' | 'LIBRARY'>('CHAT')
  const [questionId, setQuestionId] = useState<string | null>(null)
  const [historyRevision, setHistoryRevision] = useState(0)
  const [chatRevision, setChatRevision] = useState(0)
  const [trail, setTrail] = useState<Entry[]>([])
  const [entries, setEntries] = useState<Entry[]>([])
  const [selected, setSelected] = useState<Entry | null>(null)
  const [preview, setPreview] = useState<Preview | null>(null)
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [modal, setModal] = useState<Modal | null>(null)
  const [cliSettingsOpen, setCliSettingsOpen] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [connection, setConnection] = useState<ConnectionIndicator | null>(null)
  const [name, setName] = useState('')
  const [modalError, setModalError] = useState('')
  const [revision, setRevision] = useState(0)
  const uploadRef = useRef<HTMLInputElement>(null)
  const selectionAbort = useRef<AbortController | null>(null)
  const pendingOriginal = useRef<Entry | null>(null)
  const [originNotice, setOriginNotice] = useState<{ entryId: string; message: string } | null>(null)
  const activeId = session.activeWorkspaceId
  const workspace = session.workspaces.find(item => item.id === activeId)
  const parentId = trail.at(-1)?.id
  const original = area === 'ORIGINAL'

  useEffect(() => {
    if (!ready) return
    const controller = new AbortController()
    const provider = preferredProvider()
    setConnection({ provider, state: 'CHECKING', model: requestedModel(loadModel(provider)) })
    request<{ provider: string; state: string; remoteConnection: string }>(`/ai/providers/${provider}/connection`, { method: 'POST', signal: controller.signal })
      .then(value => {
        if (controller.signal.aborted) return
        setConnection({ provider, state: value.provider === provider && value.remoteConnection === 'NOT_CHECKED' ? value.state : 'CHECK_FAILED', model: requestedModel(loadModel(provider)) })
      }).catch(() => { if (!controller.signal.aborted) setConnection({ provider, state: 'CHECK_FAILED' }) })
    return () => controller.abort()
  }, [ready])

  async function refreshSession() {
    const next = await request<Session>('/session')
    setSession(next)
    setReady(true)
  }

  useEffect(() => {
    const controller = new AbortController()
    request<Session>('/session', { signal: controller.signal })
      .then(next => { setSession(next); setReady(true) })
      .catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
    return () => controller.abort()
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    selectionAbort.current?.abort()
    setEntries([])
    setSelected(null)
    setPreview(null)
    const target = pendingOriginal.current
    pendingOriginal.current = null
    if (!activeId || area === 'KNOWLEDGE') { setLoading(false); return () => controller.abort() }
    setLoading(true)
    const params = new URLSearchParams({ area })
    if (parentId) params.set('parentId', parentId)
    request<Entry[]>(`${entryPath(activeId)}?${params}`, { signal: controller.signal })
      .then(value => {
        if (controller.signal.aborted) return
        setEntries(value)
        if (target) {
          const current = value.find(item => item.id === target.id)
          if (current) setSelected(current)
          else setError('이동 중 원본이 변경·삭제되었습니다. 지식 문서에서 현재 원본을 다시 확인하세요.')
        }
      })
      .catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [activeId, area, parentId, revision])

  useEffect(() => {
    const controller = new AbortController()
    setPreview(null)
    if (activeId && selected?.kind === 'FILE') {
      request<Preview>(`${entryPath(activeId, selected.id)}/preview`, { signal: controller.signal })
        .then(value => { if (!controller.signal.aborted) setPreview(value) })
        .catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
    }
    return () => controller.abort()
  }, [activeId, selected])

  function resetNavigation(nextArea: Area = area) {
    setView('LIBRARY')
    pendingOriginal.current = null
    setOriginNotice(null)
    setArea(nextArea)
    setTrail([])
    setSelected(null)
    setError('')
  }

  function openEvidenceOriginal(location: EvidenceLocation) {
    setView('LIBRARY')
    pendingOriginal.current = location.entry
    setOriginNotice({ entryId: location.entry.id, message: location.freshness === 'STALE'
      ? '현재 원본은 인용 당시 근거와 다릅니다. 인용 행 번호를 현재 파일에 그대로 적용하지 마세요.'
      : '지식 근거에서 이동했습니다. 이동 확인 시 마지막 로컬 수집본과 일치했습니다.' })
    setError('')
    setTrail(location.ancestors)
    setArea('ORIGINAL')
    setRevision(value => value + 1)
  }

  async function switchWorkspace(id: string) {
    setBusy(true)
    setError('')
    try {
      await request(`/workspaces/${id}/active`, { method: 'PUT' })
      await refreshSession()
      resetNavigation()
      setQuestionId(null)
      setView('CHAT')
      setHistoryOpen(false)
    } catch (reason) { setError((reason as Error).message) }
    finally { setBusy(false) }
  }

  function openModal(next: Modal) {
    setName('')
    setModalError('')
    setModal(next)
  }

  async function submitModal(event: FormEvent) {
    event.preventDefault()
    if (!modal || busy) return
    setBusy(true)
    setModalError('')
    try {
      if (modal.kind === 'workspace') {
        await request('/workspaces', { method: 'POST', body: JSON.stringify({ name }) })
        await refreshSession()
        resetNavigation('ORIGINAL')
        setQuestionId(null)
      } else if (modal.kind === 'folder' && activeId) {
        await request(`/workspaces/${activeId}/folders`, { method: 'POST', body: JSON.stringify({ name, parentId }) })
      } else if (modal.kind === 'delete-workspace' && activeId) {
        await request(`/workspaces/${activeId}`, { method: 'DELETE' })
        await refreshSession()
        resetNavigation()
        setQuestionId(null)
      } else if (modal.kind === 'delete-entry' && activeId) {
        await request(entryPath(activeId, modal.entry.id), { method: 'DELETE' })
      }
      setRevision(value => value + 1)
      setModal(null)
    } catch (reason) { setModalError((reason as Error).message) }
    finally { setBusy(false) }
  }

  async function upload(file: File | undefined) {
    if (!file || !activeId) return
    if (file.size > 10 * 1024 * 1024) { setError('파일은 10 MiB 이하여야 합니다.'); return }
    setBusy(true)
    setError('')
    const body = new FormData()
    body.append('file', file)
    if (parentId) body.append('parentId', parentId)
    try {
      await request(`/workspaces/${activeId}/files`, { method: 'POST', body })
      setRevision(value => value + 1)
    } catch (reason) { setError((reason as Error).message) }
    finally { setBusy(false) }
  }

  const deleting = modal?.kind === 'delete-entry' || modal?.kind === 'delete-workspace'
  const modalTitle = modal?.kind === 'workspace' ? '새 Workspace' : modal?.kind === 'folder' ? '새 폴더' : '삭제 확인'
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand"><div className="brand-icon"><Layers3 size={23} /></div><div>Knowledge Forge<small>나만의 지식이 쌓이는 곳</small></div></div>
        <div className="workspace-label">WORKSPACE <Button variant="ghost" size="icon" aria-label="Workspace 생성" disabled={busy || !ready} onClick={() => openModal({ kind: 'workspace' })}><Plus /></Button></div>
        <label className="sr-only" htmlFor="workspace">Workspace 선택</label>
        <select id="workspace" value={activeId ?? ''} disabled={busy || !ready || !session.workspaces.length} onChange={event => void switchWorkspace(event.target.value)}>
          {!activeId && <option value="">Workspace를 만들어 주세요</option>}
          {session.workspaces.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
        </select>
        <nav aria-label="자료 영역">
          <button className={view === 'CHAT' ? 'nav-item active' : 'nav-item'} disabled={busy} onClick={() => { setView('CHAT'); setHistoryRevision(value => value + 1) }}><MessageSquare size={19} /> 대화 <ChevronRight size={15} /></button>
          <button className={view === 'LIBRARY' && original ? 'nav-item active' : 'nav-item'} disabled={busy} onClick={() => resetNavigation('ORIGINAL')}><FolderOpen size={19} /> 자료 <ChevronRight size={15} /></button>
          <details className="library-more"><summary>더 보기</summary><button className={view === 'LIBRARY' && !original ? 'nav-item active' : 'nav-item'} disabled={busy} onClick={() => resetNavigation('KNOWLEDGE')}><BookOpen size={19} /> 지식 문서 <ChevronRight size={15} /></button><button className="nav-item" disabled={busy || !activeId} onClick={() => setHistoryOpen(true)}><MessageSquare size={19} /> 답변 관리</button></details>
        </nav>
        {activeId && <QuestionHistory key={activeId} workspaceId={activeId} revision={historyRevision} selectedId={questionId} onSelect={id => { setQuestionId(id); setChatRevision(value => value + 1); setView('CHAT') }} />}
        <div className="sidebar-bottom"><ShieldCheck size={17} /><span>이 PC에 자료 저장<small>AI 전송은 건별 승인</small></span></div>
      </aside>

      <main>
        <header className="topbar"><span>{workspace?.name ?? 'Knowledge Forge'}</span><Button className="provider-trigger" variant="outline" size="sm" disabled={!ready || connection?.state === 'CHECKING'} onClick={() => setCliSettingsOpen(true)} title="로그인 확인 결과입니다. 모델 이용 가능 여부는 실제 요청 시 확인됩니다."><i data-connected={connection?.state === 'AUTHENTICATED'} />{connection?.state === 'AUTHENTICATED' ? connection.provider : connection?.state === 'CHECKING' ? '연결 확인 중' : 'AI 연결'}</Button></header>
        {cliSettingsOpen && <Suspense fallback={<p role="status">AI 연결을 여는 중…</p>}><ProviderConnectionPanel initialConnection={connection} onClose={() => setCliSettingsOpen(false)} onConnectionChange={setConnection} /></Suspense>}
        {historyOpen && activeId && <Suspense fallback={<p role="status">답변을 여는 중…</p>}><AnswerHistoryPanel key={activeId} workspaceId={activeId} onClose={() => setHistoryOpen(false)} /></Suspense>}
        <div className="page-content">
          {view === 'LIBRARY' && <div className="page-heading">
            <div><h1>{original ? '자료' : '지식 문서'}</h1><p className="subtitle">{original ? '질문에 사용할 파일과 링크를 모아 두세요.' : '원본과 따로 보관하며, 문서별 검토·게시 상태를 확인할 수 있습니다.'}</p></div>
            {workspace && <details className="secondary-tools"><summary>서재 관리</summary><Button variant="ghost" size="sm" disabled={busy} onClick={() => openModal({ kind: 'delete-workspace' })}><Trash2 /> Workspace 삭제</Button></details>}
          </div>}
          {error && <div role="alert" className="error-banner">{error}<Button variant="ghost" size="sm" disabled={busy} onClick={() => { setError(''); void refreshSession().then(() => setRevision(value => value + 1)).catch(reason => setError(reason.message)) }}>다시 시도</Button></div>}
          {!ready ? <div className="empty-state" role="status">로컬 저장소에 연결하는 중…</div> : !workspace ? (
            <section className="welcome"><PixelLandscape /><h2>첫 번째 지식 서재를 만들어 보세요</h2><p>프로젝트별로 Workspace를 나누고,<br />원본 자료를 폴더로 정리할 수 있습니다.</p><Button onClick={() => openModal({ kind: 'workspace' })}><Plus /> Workspace 만들기</Button><small>파일은 이 PC에 저장되며, 원래 파일은 변경되지 않습니다.</small></section>
          ) : view === 'CHAT' ? <ChatPanel key={`${activeId}/${chatRevision}`} workspaceId={activeId!} selectedId={questionId} connection={connection}
            onCreated={id => { setQuestionId(id); setHistoryRevision(value => value + 1) }} onDeleted={() => { setQuestionId(null); setHistoryRevision(value => value + 1); setChatRevision(value => value + 1) }} onConnect={() => setCliSettingsOpen(true)} onSources={() => resetNavigation('ORIGINAL')} /> : (
            <>
              {original && <SourcePanel manageOnly key={activeId} workspaceId={activeId!} revision={revision}
                onChanged={() => setRevision(value => value + 1)}
                onSelect={id => {
                  selectionAbort.current?.abort()
                  const controller = new AbortController()
                  selectionAbort.current = controller
                  void request<Entry>(entryPath(activeId!, id), { signal: controller.signal })
                    .then(entry => { if (!controller.signal.aborted) setSelected(entry) })
                    .catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
                }} />}
              {original && originNotice && selected?.id === originNotice.entryId && <p role="status">{originNotice.message}</p>}
              {!original ? <KnowledgePanel key={activeId} workspaceId={activeId!} onOpenOriginal={openEvidenceOriginal} onBrowseOriginal={() => setArea('ORIGINAL')} /> : <div className="explorer">
                <section className="file-pane" aria-label="자료 탐색기">
                  <div className="toolbar">
                    <div className="breadcrumbs" aria-label="폴더 경로"><button disabled={busy} onClick={() => setTrail([])}>{original ? '원본 자료' : '지식 문서'}</button>{trail.map((folder, index) => <span key={folder.id}><ChevronRight size={13} /><button disabled={busy} onClick={() => setTrail(trail.slice(0, index + 1))}>{folder.name}</button></span>)}</div>
                    <div className="toolbar-actions"><Button variant="ghost" size="icon" aria-label="새로고침" disabled={busy || loading} onClick={() => { setError(''); setRevision(value => value + 1) }}><RefreshCw /></Button>
                      {original && <><Button variant="outline" size="sm" disabled={busy} onClick={() => openModal({ kind: 'folder' })}><FolderPlus /> 새 폴더</Button><Button size="sm" disabled={busy} onClick={() => uploadRef.current?.click()}><Upload /> 파일 추가</Button><input ref={uploadRef} aria-label="파일 업로드" type="file" hidden onChange={event => { void upload(event.target.files?.[0]); event.target.value = '' }} /></>}
                    </div>
                  </div>
                  <div className="table-head"><span>이름</span><span>크기</span><span>추가한 날짜</span><span /></div>
                  {loading ? <div className="empty-state" role="status">자료를 불러오는 중…</div> : entries.length === 0 ? <div className="empty-state"><div className="empty-icon">{original ? <FolderOpen size={31} /> : <BookOpen size={31} />}</div><h2>{original ? '자료를 담을 준비가 되었어요' : '아직 생성된 지식이 없습니다'}</h2><p>{original ? '새 폴더를 만들거나 첫 번째 파일을 추가하세요.' : 'AI 지식 생성은 Phase 3에서 제공됩니다.'}</p>{original && <span>파일당 최대 10 MiB · UTF-8 텍스트 미리보기 지원</span>}</div> : (
                    <ul className="file-list">{entries.map(entry => <li key={entry.id} className={selected?.id === entry.id ? 'file-row selected' : 'file-row'}>
                      <button className="file-name" disabled={busy} onClick={() => entry.kind === 'FOLDER' ? setTrail([...trail, entry]) : setSelected(entry)}>{entry.kind === 'FOLDER' ? <Folder className="folder-icon" size={21} /> : <FileText size={21} />}<span>{entry.name}</span></button>
                      <span className="file-meta">{entry.kind === 'FOLDER' ? '—' : formatSize(entry.size)}</span><time className="file-meta">{new Date(entry.createdAt).toLocaleDateString('ko-KR')}</time>
                      <Button variant="ghost" size="icon" disabled={busy} aria-label={`${entry.name} 삭제`} onClick={() => openModal({ kind: 'delete-entry', entry })}><Trash2 /></Button>
                    </li>)}</ul>
                  )}
                  <div className="pane-footer"><ShieldCheck size={14} />{busy ? '변경사항을 저장하는 중…' : '원본 파일의 복사본을 로컬에 보관합니다.'}</div>
                </section>
                <aside className="preview-pane" aria-label="미리보기">
                  <div className="preview-heading"><span>자료 미리보기</span>{selected && <Button variant="ghost" size="icon" aria-label="미리보기 닫기" onClick={() => setSelected(null)}><X /></Button>}</div>
                  {selected ? <div className="preview-content"><FileText size={28} /><h2>{selected.name}</h2><div className="tag">보관된 원본</div><dl><dt>파일 크기</dt><dd>{formatSize(selected.size)}</dd><dt>추가한 날짜</dt><dd>{new Date(selected.createdAt).toLocaleString('ko-KR')}</dd></dl>
                    <Button variant="outline" size="sm" onClick={() => void downloadApi(`${entryPath(activeId!, selected.id)}/content`, selected.name).catch(reason => setError(reason.message))}><Download /> 원본 다운로드</Button>
                    <h3>내용</h3>{!preview ? <p role="status">미리보기를 불러오는 중…</p> : preview.supported ? <pre>{preview.text || '(빈 파일)'}</pre> : <p>{preview.reason}</p>}
                    <AnalysisPanel key={selected.id} workspaceId={activeId!} entryId={selected.id} />
                  </div> : <div className="preview-empty"><FileText size={32} /><strong>자료를 살펴보세요</strong><p>파일을 선택하면 내용과<br />상세 정보를 확인할 수 있습니다.</p></div>}
                </aside>
              </div>}
            </>
          )}
        </div>
      </main>

      <Dialog.Root open={!!modal} onOpenChange={open => { if (!open && !busy) setModal(null) }}>
        <Dialog.Portal><Dialog.Overlay className="dialog-overlay" /><Dialog.Content className="dialog-content" onEscapeKeyDown={event => { if (busy) event.preventDefault() }} onPointerDownOutside={event => { if (busy) event.preventDefault() }}>
          <Dialog.Title>{modalTitle}</Dialog.Title>
          <Dialog.Description>{deleting ? `“${modal?.kind === 'delete-entry' ? modal.entry.name : workspace?.name}” 및 내부 자료가 삭제됩니다. 이 작업은 되돌릴 수 없습니다. PC의 원래 파일은 유지됩니다.` : modal?.kind === 'workspace' ? '프로젝트나 관심 주제별로 자료를 분리해 보관하세요.' : '현재 위치에 자료를 정리할 폴더를 만듭니다.'}</Dialog.Description>
          <form onSubmit={event => void submitModal(event)}>
            {!deleting && <><label htmlFor="item-name">이름</label><input id="item-name" autoFocus required maxLength={120} value={name} onChange={event => setName(event.target.value)} placeholder={modal?.kind === 'workspace' ? '예: 나의 연구 노트' : '예: 참고 자료'} disabled={busy} /></>}
            {modalError && <p role="alert" className="modal-error">{modalError}</p>}
            <div className="dialog-actions"><Button type="button" variant="outline" disabled={busy} onClick={() => setModal(null)}>취소</Button><Button type="submit" variant={deleting ? 'destructive' : 'default'} disabled={busy || (!deleting && !name.trim())}>{busy ? '저장 중…' : deleting ? '삭제' : '만들기'}</Button></div>
          </form>
        </Dialog.Content></Dialog.Portal>
      </Dialog.Root>
    </div>
  )
}
