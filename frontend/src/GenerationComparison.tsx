import { useEffect, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'
import ProposalDecisions from './ProposalDecisions'
import ProposalAcceptance from './ProposalAcceptance'
import ProposalMerge from './ProposalMerge'

type Document = {
  card: { id: string; latestVersion: number }
  version: { number: number; title: string; markdown: string
    evidence: { snapshotId: string; path: string; startLine: number; endLine: number; quote: string; freshness: string }[] }
}
export type Comparison = { base: Document; current: Document; generated: Document }
const labels = { base: '생성 당시 문서', current: '현재 문서', generated: 'AI 최초 결과' } as const

export default function GenerationComparison({ workspaceId, jobId, disabled, onOpen, onClose }: {
  workspaceId: string; jobId: string; disabled: boolean; onOpen: (id: string) => void; onClose: () => void
}) {
  const [comparison, setComparison] = useState<Comparison | null>(null)
  const [error, setError] = useState('')
  const [refresh, setRefresh] = useState(0)
  const [showDecisions, setShowDecisions] = useState(false)
  const [showAcceptance, setShowAcceptance] = useState(false)
  const [showMerge, setShowMerge] = useState(false)
  useEffect(() => {
    const controller = new AbortController()
    setComparison(null); setError('')
    request<Comparison>(`/workspaces/${encodeURIComponent(workspaceId)}/generation-jobs/${encodeURIComponent(jobId)}/comparison`, { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setComparison(value) })
      .catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
    return () => controller.abort()
  }, [workspaceId, jobId, refresh])

  return <section className="evidence-item" aria-label="AI 결과 비교">
    <h3>AI 결과 비교 · 읽기 전용</h3>
    <p>비교 조회는 문서 저장·게시·AI 전송을 하지 않습니다. 비교 시점의 내용이며 자동 갱신되지 않습니다.</p>
    <Button variant="outline" disabled={disabled} onClick={() => setRefresh(value => value + 1)}>비교 새로고침</Button>
    <Button variant="outline" onClick={onClose}>비교 닫기</Button>
    {error && <p role="alert">{error} 원본 카드나 결과 카드가 삭제됐다면 비교할 수 없습니다. 남은 문서는 지식 목록에서 확인하세요.</p>}
    {!comparison && !error && <p role="status">비교 자료를 불러오는 중…</p>}
    {comparison && <>
      {comparison.current.version.number !== comparison.base.version.number
        ? <p role="status">생성 이후 원래 문서가 편집되었습니다. 현재 문서를 AI 결과로 덮어쓰지 마세요.</p>
        : <p>원래 문서의 버전은 생성 당시와 같습니다. 사실 검증이나 자동 반영 가능 판정은 아닙니다.</p>}
      {comparison.generated.card.latestVersion > 1 && <p>AI 결과 카드도 이후 편집되었습니다. 여기서는 최초 결과 v1을 비교합니다.</p>}
      {Object.values(comparison).some(document => document.version.evidence.some(item => item.freshness !== 'CURRENT')) &&
        <p role="status">변경되거나 삭제된 근거가 있습니다. 현재 원본과 다시 대조하세요.</p>}
      <div className="comparison-grid">{(['base', 'current', 'generated'] as const).map(kind => {
        const document = comparison[kind]
        return <section key={kind} aria-label={labels[kind]}>
          <h4>{labels[kind]} · v{document.version.number}</h4>
          <strong>{document.version.title}</strong>
          <pre>{document.version.markdown}</pre>
          <details><summary>{labels[kind]} 근거 보기</summary>{document.version.evidence.map((item, index) =>
            <div key={`${item.snapshotId}-${index}`}><p>[E{index + 1}] {item.path} · {item.startLine}~{item.endLine}행 · {item.freshness}</p><pre>{item.quote}</pre></div>)}</details>
        </section>
      })}</div>
      <p>수정 충돌이 없는 최초 결과는 전체 채택할 수 있습니다. 부분 반영 편집에서는 현재 문서를 보존하며 필요한 문장만 선택해 추가하세요. 자동 병합은 하지 않습니다.</p>
      <Button variant="outline" disabled={disabled} onClick={() => onOpen(comparison.current.card.id)}>현재 문서 열기</Button>
      <Button variant="outline" disabled={disabled} onClick={() => onOpen(comparison.generated.card.id)}>AI 결과의 최신 문서 열기</Button>
      <Button variant="outline" disabled={disabled} onClick={() => setShowDecisions(value => !value)}>제안 처리 기록 {showDecisions ? '닫기' : '열기'}</Button>
      {showDecisions && <ProposalDecisions key={`${workspaceId}-${jobId}-${refresh}`} workspaceId={workspaceId} jobId={jobId}
        sourceVersion={comparison.current.version.number} resultVersion={comparison.generated.card.latestVersion} disabled={disabled} />}
      <Button variant="outline" disabled={disabled} onClick={() => setShowAcceptance(value => !value)}>전체 채택 {showAcceptance ? '닫기' : '검토'}</Button>
      <Button variant="outline" disabled={disabled} onClick={() => setShowMerge(true)}>부분 반영 편집</Button>
      {showMerge && <ProposalMerge workspaceId={workspaceId} jobId={jobId} onClose={saved => {
        setShowMerge(false)
        if (saved) { setShowDecisions(false); setShowAcceptance(false); setRefresh(value => value + 1) }
      }} />}
      {showAcceptance && <ProposalAcceptance key={`accept-${workspaceId}-${jobId}-${refresh}`} workspaceId={workspaceId} jobId={jobId}
        sourceVersion={comparison.current.version.number} resultVersion={comparison.generated.card.latestVersion} disabled={disabled}
        eligible={comparison.current.version.number === comparison.base.version.number && comparison.generated.card.latestVersion === 1
          && comparison.generated.version.evidence.length > 0
          && Object.values(comparison).every(document => document.version.evidence.every(item => item.freshness === 'CURRENT'))}
        onApplied={() => { setShowDecisions(false); setRefresh(value => value + 1) }} />}
    </>}
  </section>
}
