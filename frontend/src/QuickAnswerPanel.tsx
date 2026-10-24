import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { preferredProvider } from './providerPreference'
import { Button } from './components/ui/button'
import AnswerJobsPanel from './AnswerJobsPanel'
import type { AnswerJob } from './answerJobEvents'

type Preview = { bundleId: string; bundleDigest: string; provider: string; requestedModel: string; digest: string; prompt: string; validation: { eligible: boolean } }
type Approval = { id: string; bundleId: string; digest: string; provider: string; requestedModel: string }
type ApprovalState = { status: string; approval: Approval | null }
type Submission = { requestId: string; approvalId: string; digest: string; approved: true }

export default function QuickAnswerPanel({ workspaceId, bundleId, bundleDigest, connectedProvider, connectedModel, requireConnection = false }: {
  workspaceId: string; bundleId: string; bundleDigest: string; connectedProvider?: string | null; connectedModel?: string | null; requireConnection?: boolean
}) {
  const [provider, setProvider] = useState<string>(() => connectedProvider ?? preferredProvider())
  const [preview, setPreview] = useState<Preview | null>(null)
  const [agreed, setAgreed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [job, setJob] = useState<AnswerJob | null>(null)
  const [submission, setSubmission] = useState<Submission | null>(null)
  const pending = useRef<AbortController | null>(null)
  const locked = useRef(false)
  const base = `/workspaces/${encodeURIComponent(workspaceId)}/evidence-bundles/${encodeURIComponent(bundleId)}`
  useEffect(() => () => pending.current?.abort(), [])

  async function prepare() {
    if (locked.current || submission || job || (requireConnection && !connectedProvider)) return
    locked.current = true
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError(''); setAgreed(false); setPreview(null)
    try {
      const value = await request<Preview>(`${base}/answer-preview`, { method: 'POST', signal: controller.signal,
        body: JSON.stringify({ provider, requestedModel: connectedModel ?? null }) })
      if (controller.signal.aborted) return
      if (value.bundleId !== bundleId || value.bundleDigest !== bundleDigest || value.provider !== provider || !value.validation.eligible
        || (connectedModel != null && value.requestedModel !== connectedModel)) {
        throw new Error('참고 자료가 변경되었거나 사용할 수 없습니다. 자료를 다시 찾아 주세요.')
      }
      setPreview(value)
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { locked.current = false; if (!controller.signal.aborted) setBusy(false) }
  }

  async function send() {
    if (locked.current || job || (requireConnection && !connectedProvider) || (!submission && (!preview || !agreed))) return
    locked.current = true
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError(''); setAgreed(false)
    try {
      let payload = submission
      if (!payload) {
        const current = await request<ApprovalState>(`${base}/answer-approval`, { signal: controller.signal })
        if (controller.signal.aborted) return
        if (current.approval && current.approval.bundleId !== bundleId) throw new Error('승인 대상이 다릅니다. 다시 확인해 주세요.')
        const approved = await request<ApprovalState>(`${base}/answer-approval`, { method: 'POST', signal: controller.signal,
          body: JSON.stringify({ digest: preview!.digest, provider: preview!.provider, requestedModel: preview!.requestedModel,
            acknowledged: true, expectedApprovalId: current.approval?.id ?? null }) })
        if (controller.signal.aborted) return
        const approval = approved.approval
        if (approved.status !== 'APPROVED' || !approval || approval.bundleId !== bundleId || approval.digest !== preview!.digest
          || approval.provider !== preview!.provider || approval.requestedModel !== preview!.requestedModel) {
          throw new Error('승인 조건이 다릅니다. 다시 확인해 주세요.')
        }
        payload = { requestId: crypto.randomUUID(), approvalId: approval.id, digest: approval.digest, approved: true }
        setSubmission(payload)
      }
      const result = await request<AnswerJob>(`${base}/answer-jobs`, { method: 'POST', signal: controller.signal, body: JSON.stringify(payload) })
      if (controller.signal.aborted) return
      if (result.bundleId !== bundleId || result.approvalId !== payload.approvalId) throw new Error('답변 대상이 다릅니다. 이전 답변에서 접수 상태를 확인해 주세요.')
      setJob(result)
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { locked.current = false; if (!controller.signal.aborted) setBusy(false) }
  }

  return <section className="quick-answer" aria-label="AI 답변 받기">
    {!job && <>
      {!requireConnection && <label className="field-label">사용할 AI<select value={provider} disabled={busy || !!submission} onChange={event => {
        setProvider(event.target.value); setPreview(null); setAgreed(false); setError('')
      }}><option value="CODEX">Codex</option><option value="CLAUDE">Claude</option></select></label>}
      {!preview && <Button disabled={busy || (requireConnection && !connectedProvider)} onClick={() => void prepare()}>이 자료로 답변 준비</Button>}
      {preview && <>
        <p>{preview.provider === 'CODEX' ? 'Codex' : 'Claude'}에 질문과 위 참고 자료를 보냅니다. 계정의 요금·이용 한도가 적용됩니다. 전송 후 창을 닫아도 작업은 계속됩니다.</p>
        <details><summary>보낼 내용 확인</summary><pre>{preview.prompt}</pre><p>모델: {preview.requestedModel || 'AI 기본값'}</p></details>
        {!submission && <><label className="check-option"><input type="checkbox" checked={agreed} disabled={busy} onChange={event => setAgreed(event.target.checked)} /><span>자료를 외부 AI에 보내는 데 동의합니다.</span></label>
          <Button disabled={busy || !agreed || (requireConnection && !connectedProvider)} onClick={() => void send()}>동의하고 답변 받기</Button></>}
      </>}
      {submission && !busy && <><p>접수 여부를 확인하지 못했습니다. 아래 버튼은 같은 요청을 확인하며 새 답변을 중복 요청하지 않습니다.</p>
        <Button onClick={() => void send()}>접수 상태 다시 확인</Button></>}
    </>}
    {busy && <p role="status">{submission ? '답변을 접수하는 중…' : '요청을 확인하는 중…'}</p>}
    {error && <p role="alert">{error}</p>}
    {job && <AnswerJobsPanel workspaceId={workspaceId} bundleId={bundleId} approval={null} initialJob={job} compact />}
  </section>
}
