import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'

export type AcceptanceState = {
  status: 'OPEN' | 'REJECTED' | 'ACCEPTED'; decisionRevision: number
  acceptance: { appliedVersion: number; note: string; acceptedAt: string; mode: 'FULL' | 'MANUAL' } | null
}

export default function ProposalAcceptance({ workspaceId, jobId, sourceVersion, resultVersion, eligible, disabled, onApplied }: {
  workspaceId: string; jobId: string; sourceVersion: number; resultVersion: number
  eligible: boolean; disabled: boolean; onApplied: () => void
}) {
  const [state, setState] = useState<AcceptanceState | null>(null)
  const [note, setNote] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [refresh, setRefresh] = useState(0)
  const pending = useRef<AbortController | null>(null)
  const path = `/workspaces/${encodeURIComponent(workspaceId)}/generation-jobs/${encodeURIComponent(jobId)}/acceptance`
  useEffect(() => {
    const controller = new AbortController()
    setState(null); setError(''); setConfirmed(false)
    request<AcceptanceState>(path, { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setState(value) })
      .catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
    return () => { controller.abort(); pending.current?.abort() }
  }, [path, refresh])

  async function accept() {
    if (!state || state.status !== 'OPEN' || !eligible || disabled || saving || !confirmed || !note.trim()) return
    const controller = new AbortController()
    pending.current = controller
    setSaving(true); setError('')
    try {
      const value = await request<AcceptanceState>(path, { method: 'POST', signal: controller.signal, body: JSON.stringify({
        expectedRevision: state.decisionRevision, expectedSourceVersion: sourceVersion, expectedResultVersion: resultVersion,
        note, confirmed,
      }) })
      if (!controller.signal.aborted) { setState(value); setNote(''); setConfirmed(false); onApplied() }
    } catch (reason) {
      if (!controller.signal.aborted) { setError((reason as Error).message); setConfirmed(false) }
    } finally {
      if (!controller.signal.aborted) setSaving(false)
    }
  }

  return <section aria-label="제안 전체 채택">
    <h4>AI 최초 결과를 새 초안으로 채택</h4>
    <p>제목과 본문 전체를 원래 문서의 새 버전으로 저장합니다. 이전 버전·게시본은 유지하고 AI 결과 카드도 남깁니다. 자동 게시·외부 전송은 없습니다.</p>
    <Button variant="outline" disabled={saving || disabled} onClick={() => setRefresh(value => value + 1)}>채택 기록 새로고침</Button>
    {error && <p role="alert">{error} 저장 결과가 불확실하면 채택 기록부터 확인하세요. 문서 충돌은 비교 새로고침 후 확인하세요.</p>}
    {!state && !error && <p role="status">채택 상태를 확인하는 중…</p>}
    {state?.acceptance ? <div role="status">
      <p>채택 완료 · 원래 문서 v{state.acceptance.appliedVersion}에 반영했습니다. 게시하려면 별도 검토가 필요합니다.</p>
      <pre>{state.acceptance.note}</pre><p>{state.acceptance.acceptedAt}</p>
      <p>반영 방식: {state.acceptance.mode === 'MANUAL' ? '수동 병합' : '전체 채택'}</p>
    </div> : state && <>
      {state.status === 'REJECTED' && <p>거절된 제안입니다. 처리 기록에서 먼저 재검토로 돌리세요.</p>}
      {!eligible && <p>생성 후 문서 편집 또는 근거 변경이 있습니다. 전체 채택은 차단됩니다. 현재 문서에서 직접 병합하세요.</p>}
      <label>채택 이유<textarea value={note} maxLength={4000} disabled={saving || disabled} onChange={event => setNote(event.target.value)} /></label>
      <label><input type="checkbox" checked={confirmed} disabled={saving || disabled} onChange={event => setConfirmed(event.target.checked)} />
        전체 제목·본문을 확인했으며, 새 초안으로 반영합니다. 사실 검증·게시 승인은 아닙니다.
      </label>
      <Button disabled={saving || disabled || !eligible || state.status !== 'OPEN' || !confirmed || !note.trim()} onClick={() => void accept()}>
        {saving ? '채택 저장 중…' : '전체 결과를 새 초안으로 채택'}
      </Button>
    </>}
  </section>
}
