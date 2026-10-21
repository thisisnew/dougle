import { useEffect, useState } from 'react'
import { request, entryPath } from './api'
import { Button } from './components/ui/button'
import { DraftForm } from './KnowledgePanel'

type Analysis = { text: string; mediaType: string; status: string; error: string | null; symbols: { name: string; kind: string; line: number; signature: string }[] }

export default function AnalysisPanel({ workspaceId, entryId }: { workspaceId: string; entryId: string }) {
  const [analysis, setAnalysis] = useState<Analysis | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setError('')
    setBusy(true)
    const action = revision > 0 ? 'analyze' : 'analysis'
    request<Analysis>(`${entryPath(workspaceId, entryId)}/${action}`, {
      method: revision > 0 ? 'POST' : 'GET', signal: controller.signal,
    }).then(setAnalysis).catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
      .finally(() => { if (!controller.signal.aborted) setBusy(false) })
    return () => controller.abort()
  }, [workspaceId, entryId, revision])
  return <section className="analysis-section" aria-label="추출 및 심볼">
    <h3>텍스트·코드 분석</h3>
    <div className="tag">{busy ? '분석 중…' : analysis?.status || 'PENDING'}</div>
    {error && <p role="alert">{error}</p>}
    {analysis?.error && <p>{analysis.error}</p>}
    <p>{analysis?.mediaType}</p>
    <Button variant="outline" size="sm" disabled={busy} onClick={() => setRevision(value => value + 1)}>다시 분석</Button>
    {!!analysis?.symbols?.length && <ul className="symbol-list">{analysis.symbols.map((symbol, index) => <li key={`${symbol.line}-${index}`}><strong>{symbol.signature}</strong><span>{symbol.kind} · {symbol.line}행</span></li>)}</ul>}
    {analysis?.text && <details><summary>추출된 텍스트</summary><pre>{analysis.text}</pre></details>}
    {!busy && analysis?.text && ['READY', 'PARTIAL'].includes(analysis.status) && <DraftForm workspaceId={workspaceId} entryId={entryId} text={analysis.text} />}
  </section>
}
