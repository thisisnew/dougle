import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'
import ConversationPreviewPanel from './ConversationPreviewPanel'

type Room = { id: string; workspaceId: string; title: string; revision: number }
type Detail = { conversation: Room; turns: { jobId: string; position: number }[] }
type Props = { workspaceId: string; selectedJobId: string | null; onSelectJob: (id: string) => void }

export default function ConversationShelf({ workspaceId, selectedJobId, onSelectJob }: Props) {
  const [rooms, setRooms] = useState<Room[]>([])
  const [detail, setDetail] = useState<Detail | null>(null)
  const [title, setTitle] = useState('')
  const [busy, setBusy] = useState(true)
  const [error, setError] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [previewOpen, setPreviewOpen] = useState(false)
  const pending = useRef<AbortController | null>(null)
  const base = `/workspaces/${encodeURIComponent(workspaceId)}/conversations`

  async function run(action: 'list' | 'open' | 'create' | 'append' | 'delete', id?: string) {
    pending.current?.abort()
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError(''); setConfirmDelete(false); setPreviewOpen(false)
    try {
      let value: Detail | null = null
      const options = { signal: controller.signal }
      if (action === 'open') value = await request<Detail>(`${base}/${encodeURIComponent(id!)}`, options)
      if (action === 'create') {
        const room = await request<Room>(base, { ...options, method: 'POST', body: JSON.stringify({ title }) })
        value = { conversation: room, turns: [] }
      }
      if (action === 'append' && detail && selectedJobId) value = await request<Detail>(`${base}/${detail.conversation.id}/turns`, {
        ...options, method: 'POST', body: JSON.stringify({ jobId: selectedJobId, expectedRevision: detail.conversation.revision }),
      })
      if (action === 'delete' && detail) await request(`${base}/${detail.conversation.id}`, { ...options, method: 'DELETE' })
      if (controller.signal.aborted) return
      if (value && (value.conversation.workspaceId !== workspaceId || (action === 'open' && value.conversation.id !== id)
        || (action === 'append' && value.conversation.id !== detail?.conversation.id))) throw new Error('보관함 범위가 다릅니다.')
      setDetail(value)
      if (action === 'create') setTitle('')
      const list = await request<Room[]>(base, options)
      if (controller.signal.aborted) return
      if (list.some(room => room.workspaceId !== workspaceId)) throw new Error('보관함 범위가 다릅니다.')
      setRooms(list)
    } catch (reason) {
      if (!controller.signal.aborted) { setDetail(null); setError((reason as Error).message) }
    } finally { if (!controller.signal.aborted) setBusy(false) }
  }

  useEffect(() => {
    void run('list')
    return () => pending.current?.abort()
  }, [])

  return <section aria-label="대화 보관함 관리">
    <h3>대화 보관함</h3>
    <p>완료 답변을 원하는 순서로 묶습니다. AI에게 이전 대화를 전송하지 않습니다.</p>
    <label>새 보관함 이름<input aria-label="새 보관함 이름" maxLength={120} value={title} disabled={busy} onChange={event => setTitle(event.target.value)} /></label>
    <Button type="button" disabled={busy || !title.trim()} onClick={() => void run('create')}>보관함 만들기</Button>
    <Button type="button" variant="outline" disabled={busy} onClick={() => void run('list')}>보관함 새로고침</Button>
    {busy && <p role="status">보관함을 불러오는 중…</p>}
    {error && <p role="alert">{error}</p>}
    <ul>{rooms.map(room => <li key={room.id}><Button type="button" variant="outline" disabled={busy}
      aria-pressed={detail?.conversation.id === room.id} onClick={() => void run('open', room.id)}>{room.title}</Button></li>)}</ul>
    {detail && <section aria-label="선택한 보관함">
      <h4>{detail.conversation.title}</h4>
      <Button type="button" disabled={busy || !selectedJobId} onClick={() => void run('append')}>현재 완료 답변 추가</Button>
      <p>먼저 이력 목록에서 완료 답변을 선택하세요. 같은 답변은 중복 추가되지 않습니다.</p>
      <ol>{detail.turns.map(turn => <li key={turn.jobId}><Button type="button" variant="outline" disabled={busy}
        onClick={() => onSelectJob(turn.jobId)}>{turn.position}번 답변 · {turn.jobId.slice(0, 8)}</Button></li>)}</ol>
      {detail.turns.length === 0 && <p>연결된 답변이 없습니다.</p>}
      <Button type="button" variant="outline" disabled={busy || detail.turns.length === 0}
        onClick={() => setPreviewOpen(value => !value)}>{previewOpen ? '문맥 미리보기 닫기' : '이전 문맥 선택'}</Button>
      {previewOpen && <ConversationPreviewPanel workspaceId={workspaceId} conversationId={detail.conversation.id}
        revision={detail.conversation.revision} turns={detail.turns} />}
      <Button type="button" variant="outline" disabled={busy} onClick={() => setConfirmDelete(true)}>보관함 삭제</Button>
      {confirmDelete && <div role="group" aria-label="보관함 삭제 확인">
        <p>보관함과 연결만 삭제합니다. 답변·근거는 남으며 실행 중 작업도 취소하지 않습니다.</p>
        <Button type="button" disabled={busy} onClick={() => void run('delete')}>보관함만 삭제 확인</Button>
        <Button type="button" variant="outline" onClick={() => setConfirmDelete(false)}>삭제 취소</Button>
      </div>}
    </section>}
  </section>
}
