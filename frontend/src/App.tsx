import { useEffect, useRef, useState, type FormEvent } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { BookOpen, ChevronRight, Download, FileText, Folder, FolderOpen, FolderPlus, Layers3, Library, Plus, RefreshCw, ShieldCheck, Trash2, Upload, X } from 'lucide-react'
import { Button } from './components/ui/button'
import { entryPath, request, type Area, type Entry, type Preview, type Session } from './api'

type Modal = { kind: 'workspace' | 'folder' } | { kind: 'delete-workspace' } | { kind: 'delete-entry'; entry: Entry }
const emptySession: Session = { workspaces: [], activeWorkspaceId: null }
const formatSize = (size: number) => size < 1024 ? `${size} B` : size < 1024 ** 2 ? `${(size / 1024).toFixed(1)} KB` : `${(size / 1024 ** 2).toFixed(1)} MB`

export default function App() {
  const [session, setSession] = useState<Session>(emptySession)
  const [ready, setReady] = useState(false)
  const [area, setArea] = useState<Area>('ORIGINAL')
  const [trail, setTrail] = useState<Entry[]>([])
  const [entries, setEntries] = useState<Entry[]>([])
  const [selected, setSelected] = useState<Entry | null>(null)
  const [preview, setPreview] = useState<Preview | null>(null)
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [modal, setModal] = useState<Modal | null>(null)
  const [name, setName] = useState('')
  const [modalError, setModalError] = useState('')
  const [revision, setRevision] = useState(0)
  const uploadRef = useRef<HTMLInputElement>(null)
  const activeId = session.activeWorkspaceId
  const workspace = session.workspaces.find(item => item.id === activeId)
  const parentId = trail.at(-1)?.id
  const original = area === 'ORIGINAL'

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
    setEntries([])
    setSelected(null)
    setPreview(null)
    if (!activeId) { setLoading(false); return () => controller.abort() }
    setLoading(true)
    const params = new URLSearchParams({ area })
    if (parentId) params.set('parentId', parentId)
    request<Entry[]>(`${entryPath(activeId)}?${params}`, { signal: controller.signal })
      .then(setEntries)
      .catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [activeId, area, parentId, revision])

  useEffect(() => {
    const controller = new AbortController()
    setPreview(null)
    if (activeId && selected?.kind === 'FILE') {
      request<Preview>(`${entryPath(activeId, selected.id)}/preview`, { signal: controller.signal })
        .then(setPreview)
        .catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
    }
    return () => controller.abort()
  }, [activeId, selected])

  function resetNavigation(nextArea: Area = area) {
    setArea(nextArea)
    setTrail([])
    setSelected(null)
    setError('')
  }

  async function switchWorkspace(id: string) {
    setBusy(true)
    setError('')
    try {
      await request(`/workspaces/${id}/active`, { method: 'PUT' })
      await refreshSession()
      resetNavigation()
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
      } else if (modal.kind === 'folder' && activeId) {
        await request(`/workspaces/${activeId}/folders`, { method: 'POST', body: JSON.stringify({ name, parentId }) })
      } else if (modal.kind === 'delete-workspace' && activeId) {
        await request(`/workspaces/${activeId}`, { method: 'DELETE' })
        await refreshSession()
        resetNavigation()
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
          <button className={original ? 'nav-item active' : 'nav-item'} disabled={busy} onClick={() => resetNavigation('ORIGINAL')}><FolderOpen size={19} /> 원본 자료 <ChevronRight size={15} /></button>
          <button className={!original ? 'nav-item active' : 'nav-item'} disabled={busy} onClick={() => resetNavigation('KNOWLEDGE')}><BookOpen size={19} /> 지식 문서 <ChevronRight size={15} /></button>
        </nav>
        <div className="sidebar-note"><Library size={21} /><strong>작은 자료에서, 연결된 지식으로.</strong><p>먼저 Workspace를 만들고<br />지식의 바탕이 될 자료를 모아 보세요.</p><span>PHASE 01 · 자료 정리</span></div>
        <div className="sidebar-bottom"><ShieldCheck size={17} /><span>이 PC에 안전하게 저장<small>외부 AI 전송 없음</small></span></div>
      </aside>

      <main>
        <header className="topbar"><span>내 지식 서재 <ChevronRight size={14} /> {workspace?.name ?? '시작하기'}</span><span className="local-badge"><i /> LOCAL WORKSPACE</span></header>
        <div className="page-content">
          <div className="page-heading">
            <div><p className="eyebrow">YOUR KNOWLEDGE, IN ONE PLACE</p><h1>{original ? '원본 자료' : '지식 문서'}</h1><p className="subtitle">{original ? '지식의 출발점이 되는 파일과 폴더를 한곳에서 관리하세요.' : '원본과 독립적으로 관리되는 검증된 지식의 공간입니다.'}</p></div>
            {workspace && <Button variant="ghost" size="sm" disabled={busy} onClick={() => openModal({ kind: 'delete-workspace' })}><Trash2 /> Workspace 삭제</Button>}
          </div>
          {error && <div role="alert" className="error-banner">{error}<Button variant="ghost" size="sm" disabled={busy} onClick={() => { setError(''); void refreshSession().then(() => setRevision(value => value + 1)).catch(reason => setError(reason.message)) }}>다시 시도</Button></div>}
          {!ready ? <div className="empty-state" role="status">로컬 저장소에 연결하는 중…</div> : !workspace ? (
            <section className="welcome"><div className="empty-icon"><Library size={36} /></div><p className="eyebrow">A SPACE FOR YOUR IDEAS</p><h2>첫 번째 지식 서재를 만들어 보세요</h2><p>프로젝트별로 Workspace를 나누고,<br />원본 자료를 폴더로 정리할 수 있습니다.</p><Button onClick={() => openModal({ kind: 'workspace' })}><Plus /> Workspace 만들기</Button><small>파일은 이 PC에 저장되며, 원래 파일은 변경되지 않습니다.</small></section>
          ) : (
            <>
              <div className="summary-strip"><span><FolderOpen size={18} />{workspace.name}</span><span>{original ? '원본 자료 보관함' : '독립 지식 보관함'}</span><span className="summary-end">현재 폴더 · {entries.length}개 항목</span></div>
              <div className="explorer">
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
                  {selected ? <div className="preview-content"><FileText size={28} /><h2>{selected.name}</h2><div className="tag">원본 · 미분석</div><dl><dt>파일 크기</dt><dd>{formatSize(selected.size)}</dd><dt>추가한 날짜</dt><dd>{new Date(selected.createdAt).toLocaleString('ko-KR')}</dd></dl>
                    <Button asChild variant="outline" size="sm"><a href={`/api${entryPath(activeId!, selected.id)}/content`} download><Download /> 원본 다운로드</a></Button>
                    <h3>내용</h3>{!preview ? <p role="status">미리보기를 불러오는 중…</p> : preview.supported ? <pre>{preview.text || '(빈 파일)'}</pre> : <p>{preview.reason}</p>}
                  </div> : <div className="preview-empty"><FileText size={32} /><strong>자료를 살펴보세요</strong><p>파일을 선택하면 내용과<br />상세 정보를 확인할 수 있습니다.</p></div>}
                </aside>
              </div>
              <p className="phase-note"><ShieldCheck size={15} /> 현재 단계에서는 자료를 저장하고 탐색합니다. 수집·검색·AI 분석은 후속 단계에서 연결됩니다.</p>
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
