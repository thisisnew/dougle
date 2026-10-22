import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'

export type RelationSeed = { kind: 'ORIGINAL' | 'KNOWLEDGE'; id: string; version: number | null; title: string }
type Related = { target: {
  kind: 'ORIGINAL' | 'KNOWLEDGE'; id: string; title: string; version: number | null; path: string
  domains: string[]; relation: string; detail: string
}; freshness: string }
const labels: Record<string, string> = {
  SAME_SYMBOL: '동일 이름·종류의 선언 심볼', CITED_SOURCE: '게시본이 인용한 원본 위치',
  CITES_SOURCE: '이 원본 위치를 인용한 게시본', SHARED_EVIDENCE: '같은 원본 위치를 인용한 게시본',
}

export default function RelationPanel({ workspaceId, seed, domains, onClose, onOriginal, onKnowledge }: {
  workspaceId: string; seed: RelationSeed; domains: string[]; onClose: () => void
  onOriginal: (id: string) => void; onKnowledge: (id: string, version: number) => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [result, setResult] = useState<{ items: Related[]; truncated: boolean } | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    const element = dialog.current!
    element.showModal()
    return () => element.close()
  }, [])
  useEffect(() => {
    const controller = new AbortController()
    setResult(null)
    setError('')
    const params = new URLSearchParams({ kind: seed.kind, id: seed.id })
    if (seed.version !== null) params.set('version', String(seed.version))
    domains.forEach(domain => params.append('domains', domain))
    request<{ items: Related[]; truncated: boolean }>(`/workspaces/${encodeURIComponent(workspaceId)}/relations?${params}`, { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setResult(value) })
      .catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
    return () => controller.abort()
  }, [workspaceId, seed, domains])
  return <dialog ref={dialog} className="proposal-merge" aria-label="관련 자료 탐색" onCancel={event => { event.preventDefault(); onClose() }}>
    <Button variant="outline" onClick={onClose}>닫기</Button>
    <h2>{seed.title} · 관련 자료</h2>
    <p>같은 출처 위치·선언 심볼을 따라 찾습니다. 의미적 유사성이나 함수 호출 관계가 아닙니다. AI를 호출하지 않습니다.</p>
    <p>현재 로컬 원본은 게시 당시 내용과 다를 수 있습니다. CURRENT도 원격 최신성·사실 검증을 보장하지 않습니다.</p>
    {error ? <p role="alert">{error}</p> : !result ? <p role="status">연결된 자료를 찾는 중…</p> : <>
      <p>{result.items.length}개{result.truncated ? ' · 최대 30개 표시, 더 있음' : ''} · {domains.length ? domains.join(', ') : '전체 도메인'}</p>
      {result.items.length === 0 && <p>조건에 맞는 연결이 없습니다.</p>}
      <ul>{result.items.map(item => <li key={`${item.target.kind}:${item.target.id}`}>
        <Button variant="outline" onClick={() => {
          onClose()
          if (item.target.kind === 'ORIGINAL') onOriginal(item.target.id)
          else if (item.target.version !== null) onKnowledge(item.target.id, item.target.version)
        }}>{item.target.title}</Button>
        <p>{labels[item.target.relation] || item.target.relation} · {item.target.detail}</p>
        <small>{item.target.domains.join(', ') || '분류 없음'} · {item.freshness} · {item.target.path}{item.target.version !== null ? ` · 게시 v${item.target.version}` : ''}</small>
      </li>)}</ul>
    </>}
  </dialog>
}
