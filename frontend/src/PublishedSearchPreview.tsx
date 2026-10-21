import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'
import KnowledgeMarkdown from './KnowledgeMarkdown'

type Document = { version: { number: number; title: string; markdown: string; evidence: {
  snapshotId: string; name: string; startLine: number; endLine: number; quote: string; freshness: string
}[] } }

export default function PublishedSearchPreview({ workspaceId, cardId, version, onClose }: {
  workspaceId: string; cardId: string; version: number; onClose: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [document, setDocument] = useState<Document | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    const element = dialog.current!
    element.showModal()
    return () => element.close()
  }, [])
  useEffect(() => {
    const controller = new AbortController()
    setDocument(null)
    setError('')
    request<Document>(`/workspaces/${encodeURIComponent(workspaceId)}/knowledge/${encodeURIComponent(cardId)}/versions/${version}`,
      { signal: controller.signal }).then(value => { if (!controller.signal.aborted) setDocument(value) })
      .catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
    return () => controller.abort()
  }, [workspaceId, cardId, version])
  return <dialog ref={dialog} className="proposal-merge" aria-label="검색된 게시 지식" onCancel={event => { event.preventDefault(); onClose() }}>
    <Button variant="outline" onClick={onClose}>닫기</Button>
    <h2>검색 당시 게시본 v{version}</h2>
    <p>읽기 전용입니다. 이후 새 게시본이 생겼을 수 있습니다. 최신 결과는 다시 검색하세요.</p>
    {error ? <p role="alert">{error}</p> : !document ? <p role="status">게시본을 불러오는 중…</p> : <>
      <h3>{document.version.title}</h3>
      <KnowledgeMarkdown markdown={document.version.markdown} />
      <h3>출처 · 현재 근거 상태</h3>
      <p>CURRENT는 수집본과 같다는 뜻이며 원격 자료의 최신성이나 자동 사실 검증을 보장하지 않습니다.</p>
      {document.version.evidence.map((item, index) => <details key={`${item.snapshotId}-${index}`}>
        <summary>[E{index + 1}] {item.name} · {item.startLine}–{item.endLine}행 · {item.freshness}</summary>
        <pre>{item.quote}</pre>
      </details>)}
    </>}
  </dialog>
}
