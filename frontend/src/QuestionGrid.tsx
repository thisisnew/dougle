import { useEffect, useState } from 'react'
import { History, Plus } from 'lucide-react'
import { request } from './api'
import { Button } from './components/ui/button'

type Question = { id: string; question: string; createdAt: string }

export default function QuestionGrid({ workspaceId, onSelect }: { workspaceId: string; onSelect: (id: string | null) => void }) {
  const [items, setItems] = useState<Question[]>([])
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setItems([]); setError('')
    request<Question[]>(`/workspaces/${encodeURIComponent(workspaceId)}/evidence-bundles`, { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setItems(value) })
      .catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [workspaceId, revision])
  const visible = items.filter(item => item.question.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()))
  return <section aria-label="질문 기록" className="question-grid-page">
    <div className="page-heading"><h1>질문 기록</h1><Button onClick={() => onSelect(null)}><Plus /> 새 질문</Button></div>
    <label className="sr-only" htmlFor="history-query">질문 찾기</label><input id="history-query" placeholder="질문 찾기" maxLength={256} value={query} onChange={event => setQuery(event.target.value)} />
    {loading ? <p role="status">기록을 불러오는 중…</p> : error ? <div role="alert"><p>{error}</p><Button variant="outline" onClick={() => setRevision(value => value + 1)}>다시 불러오기</Button></div> : !visible.length ? <p className="empty-state">{items.length ? '일치하는 질문이 없습니다.' : '아직 질문 기록이 없습니다.'}</p> : <ul className="question-grid">{visible.map(item => <li key={item.id}><button className="question-card" onClick={() => onSelect(item.id)} aria-label={item.question}><History size={20} /><strong>{item.question}</strong><time dateTime={item.createdAt}>{new Date(item.createdAt).toLocaleDateString('ko-KR')}</time></button></li>)}</ul>}
  </section>
}
