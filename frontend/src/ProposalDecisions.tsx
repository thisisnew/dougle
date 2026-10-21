import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'
import type { AcceptanceState } from './ProposalAcceptance'

type Decision = { revision: number; status: 'OPEN' | 'REJECTED'; sourceVersion: number; resultVersion: number; note: string; createdAt: string }

export default function ProposalDecisions({ workspaceId, jobId, sourceVersion, resultVersion, disabled }: {
  workspaceId: string; jobId: string; sourceVersion: number; resultVersion: number; disabled: boolean
}) {
  const [history, setHistory] = useState<Decision[] | null>(null)
  const [note, setNote] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [refresh, setRefresh] = useState(0)
  const [accepted, setAccepted] = useState(false)
  const pending = useRef<AbortController | null>(null)
  const path = `/workspaces/${encodeURIComponent(workspaceId)}/generation-jobs/${encodeURIComponent(jobId)}/decisions`
  useEffect(() => {
    const controller = new AbortController()
    setHistory(null); setError('')
    Promise.all([request<Decision[]>(path, { signal: controller.signal }),
      request<AcceptanceState>(path.replace(/decisions$/, 'acceptance'), { signal: controller.signal })])
      .then(([value, state]) => { if (!controller.signal.aborted) { setHistory(value); setAccepted(state.status === 'ACCEPTED') } })
      .catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
    return () => { controller.abort(); pending.current?.abort() }
  }, [path, refresh])

  async function decide() {
    if (!history || saving || disabled || accepted) return
    const controller = new AbortController()
    pending.current = controller
    setSaving(true); setError('')
    try {
      const value = await request<Decision[]>(path, { method: 'POST', signal: controller.signal, body: JSON.stringify({
        expectedRevision: history[0]?.revision ?? 0, expectedSourceVersion: sourceVersion, expectedResultVersion: resultVersion,
        status: history[0]?.status === 'REJECTED' ? 'OPEN' : 'REJECTED', note,
      }) })
      if (!controller.signal.aborted) { setHistory(value); setNote('') }
    } catch (reason) {
      if (!controller.signal.aborted) setError((reason as Error).message)
    } finally {
      if (!controller.signal.aborted) setSaving(false)
    }
  }

  return <section aria-label="제안 처리 기록">
    <h4>제안 거절·재검토</h4>
    <p>처리 이유만 저장합니다. 원래 문서·AI 결과·게시본은 바꾸거나 삭제하지 않습니다. 재검토는 AI 재실행이 아닙니다.</p>
    <Button variant="outline" disabled={saving || disabled} onClick={() => setRefresh(value => value + 1)}>처리 기록 새로고침</Button>
    {error && <p role="alert">{error} 저장 결과가 불확실하면 기록을 새로고침하세요. 문서 변경 충돌은 비교 새로고침이 필요합니다.</p>}
    {!history && !error && <p role="status">처리 기록을 불러오는 중…</p>}
    {history && <>
      <p>처리 상태: {accepted ? '채택됨' : history[0]?.status === 'REJECTED' ? '거절됨' : '검토 대기'}</p>
      <label>처리 이유<textarea value={note} maxLength={4000} disabled={saving || disabled || accepted} onChange={event => setNote(event.target.value)} /></label>
      <Button variant="outline" disabled={saving || disabled || accepted || !note.trim()} onClick={() => void decide()}>
        {saving ? '기록 중…' : history[0]?.status === 'REJECTED' ? '재검토로 돌리기' : '제안 거절 기록'}
      </Button>
      <p>전체 채택 여부와 반영 버전은 전체 채택 검토에서 확인하세요. 채택 후 거절·재검토로 돌릴 수 없습니다. 아래 결과 버전은 판단 당시 최신 카드 버전이며 비교 본문은 최초 v1입니다.</p>
      {history.map(item => <div key={item.revision}>
        <strong>#{item.revision} {item.status === 'REJECTED' ? '거절' : '재검토'}</strong>
        <p>원래 문서 v{item.sourceVersion} / 결과 카드 v{item.resultVersion} · {item.createdAt}</p><pre>{item.note}</pre>
      </div>)}
    </>}
  </section>
}
