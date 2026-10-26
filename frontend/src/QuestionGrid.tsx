import { useEffect, useRef, useState } from 'react'
import { History, Search, Trash2 } from 'lucide-react'
import { request } from './api'
import { Button } from './components/ui/button'
import DeleteConfirmation from './DeleteConfirmation'

type Question = { id: string; question: string; createdAt: string; questions?: string[] }

type Props = { workspaceId: string | null; onSelect: (id: string | null) => void; onDeleted?: (id: string) => void }
export default function QuestionGrid(props: Props) {
  return <ScopedQuestionGrid key={props.workspaceId} {...props} />
}
function ScopedQuestionGrid({ workspaceId, onSelect, onDeleted }: Props) {
  const [items, setItems] = useState<Question[]>([])
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  const [selected, setSelected] = useState<string[]>([])
  const [deleting, setDeleting] = useState<Question[]>([])
  const [deletingBusy, setDeletingBusy] = useState(false)
  const [deleteError, setDeleteError] = useState('')
  const pendingDelete = useRef<AbortController | null>(null)
  useEffect(() => () => pendingDelete.current?.abort(), [])
  async function remove() {
    if (!workspaceId || !deleting.length || pendingDelete.current) return
    const controller = new AbortController()
    pendingDelete.current = controller
    setDeletingBusy(true); setDeleteError('')
    try {
      const failed: Question[] = []
      for (const item of deleting) {
        if (controller.signal.aborted) return
        try {
          await request(`/workspaces/${encodeURIComponent(workspaceId)}/chat-threads/${encodeURIComponent(item.id)}`, { method: 'DELETE', signal: controller.signal })
          if (controller.signal.aborted) return
          setItems(previous => previous.filter(value => value.id !== item.id))
          setSelected(previous => previous.filter(id => id !== item.id))
          onDeleted?.(item.id)
        } catch {
          if (controller.signal.aborted) return
          failed.push(item)
        }
      }
      setDeleting(failed)
      if (failed.length) setDeleteError(`${failed.length}개 질문을 삭제하지 못했습니다. 다시 시도해 주세요.`)
    } catch (reason) { if (!controller.signal.aborted) setDeleteError((reason as Error).message) }
    finally { if (!controller.signal.aborted) { pendingDelete.current = null; setDeletingBusy(false) } }
  }
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setItems([]); setSelected([]); setError('')
    if (!workspaceId) { setLoading(false); return () => controller.abort() }
    request<Question[]>(`/workspaces/${encodeURIComponent(workspaceId)}/chat-threads`, { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setItems(value) })
      .catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [workspaceId, revision])
  const visible = items.filter(item => (item.questions ?? [item.question]).some(question => question.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase())))
  return <section aria-label="질문 기록" className="question-grid-page">
    <div className="page-heading"><h1>질문 기록</h1></div>
    <div className="history-search" role="search"><Search size={18} aria-hidden="true" /><label className="sr-only" htmlFor="history-query">질문 찾기</label><input id="history-query" type="search" placeholder="질문 찾기" maxLength={256} value={query} disabled={deletingBusy} onChange={event => { setQuery(event.target.value); setSelected([]) }} /></div>
    {!loading && !error && visible.length > 0 && <div className="history-selection"><label><input type="checkbox" aria-label="표시된 질문 전체 선택" disabled={deletingBusy} checked={visible.every(item => selected.includes(item.id))} ref={element => { if (element) element.indeterminate = selected.length > 0 && !visible.every(item => selected.includes(item.id)) }} onChange={event => setSelected(event.target.checked ? visible.map(item => item.id) : [])} />전체 선택</label><Button variant="outline" disabled={!selected.length || deletingBusy} onClick={() => { setDeleting(items.filter(item => selected.includes(item.id))); setDeleteError('') }}><Trash2 size={16} />선택 삭제{selected.length ? ` (${selected.length})` : ''}</Button></div>}
{loading ? <div className="history-loading" role="status" aria-label="질문 기록 불러오는 중"><div className="history-skeleton" aria-hidden="true"><span /><span /><span /></div></div> : error ? <div role="alert"><p>{error}</p><Button variant="outline" onClick={() => setRevision(value => value + 1)}>다시 불러오기</Button></div> : !visible.length ? <p className="empty-state">{items.length ? '일치하는 질문이 없습니다.' : '질문 기록이 없습니다.'}</p> : <ul className="question-grid">{visible.map(item => <li key={item.id}><input className="question-select" type="checkbox" aria-label={`${item.question} 선택`} checked={selected.includes(item.id)} disabled={deletingBusy} onChange={event => setSelected(previous => event.target.checked ? [...previous, item.id] : previous.filter(id => id !== item.id))} /><button className="question-card" disabled={deletingBusy} onClick={() => onSelect(item.id)} aria-label={item.question}><History size={20} /><strong>{item.question}</strong><time dateTime={item.createdAt}>{new Date(item.createdAt).toLocaleDateString('ko-KR')}</time></button></li>)}</ul>}
    {deleting.length > 0 && <DeleteConfirmation name={deleting.length === 1 ? deleting[0].question : `선택한 질문 ${deleting.length}개`} busy={deletingBusy} error={deleteError} onCancel={() => setDeleting([])} onConfirm={() => void remove()} />}
  </section>
}
