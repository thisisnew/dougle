import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'

type Preview = { digest: string; provider: string; requestedModel: string }
type Approval = Preview & { id: string; bundleId: string; approvedAt: string; expiresAt: string; revokedAt: string | null }
type State = { status: string; approval: Approval | null }
const names: Record<string, string> = { NONE: '승인 없음', APPROVED: '확인 시점에 승인 유효', REVOKED: '승인 철회됨', EXPIRED: '승인 만료됨', INVALIDATED: '근거 또는 요청 변경으로 승인 사용 불가' }

export default function AnswerApprovalPanel({ workspaceId, bundleId, preview }: {
  workspaceId: string; bundleId: string; preview: Preview | null
}) {
  const [state, setState] = useState<State | null>(null)
  const [agreed, setAgreed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const pending = useRef<AbortController | null>(null)
  const base = `/workspaces/${encodeURIComponent(workspaceId)}/evidence-bundles/${encodeURIComponent(bundleId)}/answer-approval`
  useEffect(() => () => pending.current?.abort(), [])

  async function act(action: 'get' | 'approve' | 'revoke') {
    if (busy || (action === 'approve' && (!preview || !state || !agreed)) || (action === 'revoke' && !state?.approval)) return
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError(''); setAgreed(false)
    try {
      const value = await request<State>(action === 'revoke' ? `${base}/${encodeURIComponent(state!.approval!.id)}` : base, {
        signal: controller.signal, method: action === 'get' ? 'GET' : action === 'approve' ? 'POST' : 'DELETE',
        ...(action === 'approve' ? { body: JSON.stringify({ ...preview, acknowledged: true, expectedApprovalId: state?.approval?.id ?? null }) } : {}),
      })
      if (controller.signal.aborted) return
      if (value.approval && value.approval.bundleId !== bundleId) throw new Error('승인 대상이 다릅니다. 다시 확인하세요.')
      if (action === 'approve' && (!value.approval || value.approval.digest !== preview!.digest)) throw new Error('승인 조건이 다릅니다. 다시 확인하세요.')
      setState(value)
    } catch (reason) {
      if (!controller.signal.aborted) { setState(null); setError((reason as Error).message) }
    } finally { if (!controller.signal.aborted) setBusy(false) }
  }

  return <section aria-label="답변 요청 승인" className="answer-approval">
    <h3>이 요청만 승인 · 아직 전송하지 않음</h3>
    <p>Codex/Claude는 외부 서비스입니다. 현재 코딩 대화와 별도 요청이며, 인용문에 민감한 정보가 없는지 확인하세요.</p>
    <p>승인은 10분간 이 요청에만 해당합니다. 실제 답변 전송 기능은 아직 없습니다. 창을 닫아도 서버가 받은 승인은 저장될 수 있습니다.</p>
    <Button variant="outline" disabled={busy} onClick={() => void act('get')}>승인 상태 확인</Button>
    {state && <p role="status">{names[state.status] || state.status}</p>}
    {state?.approval && <div>
      <p>저장된 대상: {state.approval.provider} · {state.approval.requestedModel || 'CLI 기본값'}</p>
      <p>만료 시각: {new Date(state.approval.expiresAt).toLocaleString('ko-KR')} · 상태는 조회 시점 기준입니다.</p>
      {preview && state.approval.digest !== preview.digest && <p>현재 미리보기와 다른 요청의 승인입니다. 현재 요청에는 사용할 수 없습니다.</p>}
      <Button variant="outline" disabled={busy || state.status === 'REVOKED'} onClick={() => void act('revoke')}>저장된 승인 철회</Button>
    </div>}
    {preview && <div>
      <p>승인할 대상: {preview.provider} · {preview.requestedModel || 'CLI 기본값 (실행 모델 미확인)'}</p>
      <label><input type="checkbox" checked={agreed} disabled={busy || !state} onChange={event => setAgreed(event.target.checked)} />요청 전문과 외부 전송 대상을 확인했으며, 이 요청에 동의합니다.</label>
      {!state && <p>먼저 승인 상태를 확인하세요.</p>}
      <Button disabled={busy || !state || !agreed} onClick={() => void act('approve')}>이 요청 승인 저장 (전송 안 함)</Button>
    </div>}
    {busy && <p role="status">승인 상태 처리 중…</p>}
    {error && <p role="alert">{error} 승인 상태를 다시 확인하세요.</p>}
  </section>
}
