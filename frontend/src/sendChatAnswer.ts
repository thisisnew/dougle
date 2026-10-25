import { request } from './api'
import type { AnswerJob } from './answerJobEvents'

export type ChatSubmission = { requestId: string; approvalId: string; digest: string; approved: true }
type Approval = { id: string; bundleId: string; digest: string; provider: string; requestedModel: string }
type ApprovalState = { status: string; approval: Approval | null }

export async function sendChatAnswer(workspaceId: string, bundleId: string, bundleDigest: string, provider: string,
  model: string | null, signal: AbortSignal, remember: (submission: ChatSubmission) => void, existing?: ChatSubmission) {
  const base = `/workspaces/${encodeURIComponent(workspaceId)}/evidence-bundles/${encodeURIComponent(bundleId)}`
  let submission = existing
  if (!submission) {
    const preview = await request<{ bundleId: string; bundleDigest: string; provider: string; requestedModel: string; digest: string; validation: { eligible: boolean } }>(`${base}/answer-preview`, {
      method: 'POST', signal, body: JSON.stringify({ provider, requestedModel: model }),
    })
    signal.throwIfAborted()
    if (preview.bundleId !== bundleId || preview.bundleDigest !== bundleDigest || preview.provider !== provider
      || (model !== null && preview.requestedModel !== model) || !preview.validation.eligible) throw new Error('자료가 변경되었습니다. 질문을 다시 보내 주세요.')
    const current = await request<ApprovalState>(`${base}/answer-approval`, { signal })
    signal.throwIfAborted()
    if (current.approval && current.approval.bundleId !== bundleId) throw new Error('질문의 자료를 확인하지 못했습니다.')
    const result = await request<ApprovalState>(`${base}/answer-approval`, { method: 'POST', signal,
      body: JSON.stringify({ digest: preview.digest, provider, requestedModel: preview.requestedModel, acknowledged: true, expectedApprovalId: current.approval?.id ?? null }),
    })
    signal.throwIfAborted()
    const approval = result.approval
    if (result.status !== 'APPROVED' || !approval || approval.bundleId !== bundleId || approval.digest !== preview.digest
      || approval.provider !== provider || approval.requestedModel !== preview.requestedModel) throw new Error('요청 조건이 변경되었습니다. 질문을 다시 보내 주세요.')
    submission = { requestId: crypto.randomUUID(), approvalId: approval.id, digest: approval.digest, approved: true }
    remember(submission)
  }
  const job = await request<AnswerJob>(`${base}/answer-jobs`, { method: 'POST', signal, body: JSON.stringify(submission) })
  signal.throwIfAborted()
  if (job.bundleId !== bundleId || job.approvalId !== submission.approvalId) throw new Error('답변 접수 상태를 확인하지 못했습니다.')
  return job
}
