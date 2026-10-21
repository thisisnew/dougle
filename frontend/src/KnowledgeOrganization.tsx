import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'

type Folder = { id: string; parentId: string | null; name: string; revision: number }
type Catalog = { folders: Folder[]; placements: { cardId: string; folderId: string | null; revision: number }[] }

export default function KnowledgeOrganization({ workspaceId, cards, selected, disabled, revision, onFilter, onBusy }: {
  workspaceId: string; cards: { id: string; title: string }[]; selected: string; disabled: boolean; revision: number
  onFilter: (ids: string[] | null) => void; onBusy: (busy: boolean) => void
}) {
  const [catalog, setCatalog] = useState<Catalog | null>(null)
  const [folderId, setFolderId] = useState<string | null>(null)
  const [all, setAll] = useState(true)
  const [name, setName] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [refresh, setRefresh] = useState(0)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [editing, setEditing] = useState<Folder | null>(null)
  const [editName, setEditName] = useState('')
  const [editParent, setEditParent] = useState('')
  const pending = useRef<AbortController | null>(null)
  const base = `/workspaces/${encodeURIComponent(workspaceId)}/knowledge-organization`
  useEffect(() => () => pending.current?.abort(), [])
  useEffect(() => {
    const controller = new AbortController()
    setCatalog(null)
    request<Catalog>(base, { signal: controller.signal }).then(value => {
      if (!controller.signal.aborted) { setCatalog(value); setError('') }
    }).catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
    return () => controller.abort()
  }, [base, refresh, revision])
  const folder = catalog?.folders.find(item => item.id === folderId)
  const missing = !all && folderId !== null && catalog !== null && !folder
  useEffect(() => {
    if (all) onFilter(null)
    else onFilter(catalog && !missing ? cards.filter(card =>
      (catalog.placements.find(item => item.cardId === card.id)?.folderId ?? null) === folderId).map(card => card.id) : [])
  }, [all, catalog, cards, folderId, missing, onFilter])

  function navigate(id: string | null, showAll = false) {
    setFolderId(id); setAll(showAll); setConfirmDelete(false); setName(''); setError('')
    setEditing(null)
  }

  async function mutate(action: 'create' | 'delete' | 'move' | 'update') {
    if (busy || disabled || !catalog || missing) return
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); onBusy(true); setError('')
    try {
      if (action === 'create') {
        await request(`${base}/folders`, { method: 'POST', signal: controller.signal, body: JSON.stringify({ parentId: folderId, name }) })
      } else if (action === 'delete') {
        await request(`${base}/folders/${folderId}`, { method: 'DELETE', signal: controller.signal })
      } else if (action === 'update' && editing) {
        await request(`${base}/folders/${editing.id}`, { method: 'PUT', signal: controller.signal,
          body: JSON.stringify({ parentId: editParent || null, name: editName, expectedRevision: editing.revision }) })
      } else {
        await request(`${base}/cards/${selected}`, { method: 'PUT', signal: controller.signal,
          body: JSON.stringify({ folderId, expectedRevision: catalog.placements.find(item => item.cardId === selected)?.revision ?? 0 }) })
      }
      if (!controller.signal.aborted) {
        if (action === 'delete') navigate(folder?.parentId ?? null)
        setName(''); setConfirmDelete(false); setEditing(null); setRefresh(value => value + 1)
      }
    } catch (reason) { if (!controller.signal.aborted) setError(`${(reason as Error).message} 폴더 새로고침 후 다시 확인하세요.`) }
    finally { if (!controller.signal.aborted) { setBusy(false); onBusy(false) } }
  }

  const blocked = disabled || busy
  const selectedCard = cards.find(card => card.id === selected)
  function folderPath(item: Folder) {
    const names: string[] = []
    const visited = new Set<string>()
    let current: Folder | undefined = item
    while (current && !visited.has(current.id)) {
      visited.add(current.id); names.unshift(current.name)
      current = catalog?.folders.find(candidate => candidate.id === current?.parentId)
    }
    return `지식 루트 / ${names.join(' / ')}`
  }
  const trail: Folder[] = []
  let ancestor = folder
  while (ancestor && !trail.some(item => item.id === ancestor!.id)) {
    trail.unshift(ancestor)
    ancestor = catalog?.folders.find(item => item.id === ancestor!.parentId)
  }
  return <section className="draft-section" aria-label="지식 폴더">
    <h3>독립 지식 폴더</h3>
    <p>새 초안은 루트에 저장됩니다. 카드를 선택한 뒤 이동할 폴더를 열고 배치하세요. 문서 버전·게시본·원본은 바뀌지 않습니다.</p>
    <div className="source-actions">
      <Button variant="outline" disabled={blocked} onClick={() => navigate(null, true)}>전체 지식</Button>
      <Button variant="outline" disabled={blocked} onClick={() => navigate(null)}>지식 루트</Button>
      <Button variant="outline" disabled={blocked} onClick={() => setRefresh(value => value + 1)}>폴더 새로고침</Button>
    </div>
    <nav aria-label="지식 폴더 경로">{all ? '전체 지식' : '지식 루트'}{trail.map(item =>
      <Button key={item.id} variant="ghost" disabled={blocked} onClick={() => navigate(item.id)}> / {item.name}</Button>)}</nav>
    {error && <p role="alert">{error}</p>}
    {missing && <p role="alert">이 폴더는 삭제되었습니다. 지식 루트로 이동하세요.</p>}
    {!catalog && !error && <p role="status">폴더를 불러오는 중…</p>}
    {catalog && !missing && <>
      <ul>{catalog.folders.filter(item => item.parentId === folderId).map(item => <li key={item.id}>
        <Button variant="ghost" disabled={blocked} onClick={() => navigate(item.id)}>폴더: {item.name}</Button>
      </li>)}</ul>
      {!all && <>
        <form className="knowledge-form" onSubmit={event => { event.preventDefault(); void mutate('create') }}>
          <label>새 지식 폴더 이름<input required maxLength={120} value={name} disabled={blocked} onChange={event => setName(event.target.value)} /></label>
          <Button type="submit" disabled={blocked || !name.trim()}>지식 폴더 생성</Button>
        </form>
        {selectedCard && <div><p>선택한 카드: {selectedCard.title}</p>
          <Button disabled={blocked || (catalog.placements.find(item => item.cardId === selected)?.folderId ?? null) === folderId}
            onClick={() => void mutate('move')}>선택한 카드를 현재 폴더로 이동</Button></div>}
        {folder && <Button variant="outline" disabled={blocked} onClick={() => setConfirmDelete(true)}>현재 지식 폴더 삭제</Button>}
        {folder && !editing && <Button variant="outline" disabled={blocked} onClick={() => {
          setEditing(folder); setEditName(folder.name); setEditParent(folder.parentId ?? ''); setConfirmDelete(false)
        }}>폴더 이름·위치 변경</Button>}
        {editing && <form className="knowledge-form" onSubmit={event => { event.preventDefault(); void mutate('update') }}>
          <p>하위 폴더와 카드를 함께 옮깁니다. 문서 내용은 바꾸지 않습니다. 충돌하면 입력을 보존하므로 복사한 뒤 취소·새로고침하세요.</p>
          <label>변경할 폴더 이름<input required maxLength={120} value={editName} disabled={blocked} onChange={event => setEditName(event.target.value)} /></label>
          <label>이동할 상위 폴더<select value={editParent} disabled={blocked} onChange={event => setEditParent(event.target.value)}>
            <option value="">지식 루트</option>
            {catalog.folders.filter(item => item.id !== editing.id).map(item => <option key={item.id} value={item.id}>{folderPath(item)}</option>)}
          </select></label>
          <p>자신이나 하위 폴더로 이동하면 서버에서 거절합니다.</p>
          <Button type="submit" disabled={blocked || !editName.trim()}>폴더 변경 저장</Button>
          <Button type="button" variant="outline" disabled={blocked} onClick={() => setEditing(null)}>폴더 변경 취소</Button>
        </form>}
        {confirmDelete && <div role="group" aria-label="지식 폴더 삭제 확인">
          <p>빈 폴더만 삭제합니다. 카드나 하위 폴더가 있으면 삭제하지 않습니다.</p>
          <Button variant="destructive" disabled={blocked} onClick={() => void mutate('delete')}>빈 폴더 삭제 확인</Button>
          <Button variant="outline" disabled={blocked} onClick={() => setConfirmDelete(false)}>폴더 삭제 취소</Button>
        </div>}
      </>}
    </>}
  </section>
}
