import type { PlanSubmission } from './sendConversationPlan'

type Scope = { workspaceId: string; bundleId: string; bundleDigest: string }
const key = (scope: Scope) => `dougle.plan-submission.${encodeURIComponent(scope.workspaceId)}.${encodeURIComponent(scope.bundleId)}`

export function readPlanSubmission(scope: Scope): PlanSubmission | undefined {
  try {
    const raw = sessionStorage.getItem(key(scope))
    if (raw === null) return undefined
    const value = JSON.parse(raw)
    const submission = value?.submission
    if (value.workspaceId !== scope.workspaceId || value.bundleId !== scope.bundleId || value.bundleDigest !== scope.bundleDigest
      || !submission || submission.bundleId !== scope.bundleId || submission.approved !== true
      || !['CODEX', 'CLAUDE'].includes(submission.provider)
      || (submission.requestedModel !== null && typeof submission.requestedModel !== 'string')
      || typeof submission.requestId !== 'string' || !submission.requestId || typeof submission.digest !== 'string' || !submission.digest) throw new Error()
    return { requestId: submission.requestId, bundleId: submission.bundleId, provider: submission.provider,
      requestedModel: submission.requestedModel, digest: submission.digest, approved: true }
  } catch { throw new Error('저장된 조사 접수 상태를 확인하지 못했습니다. 자동으로 다시 보내지 않습니다.') }
}

export function savePlanSubmission(scope: Scope, submission: PlanSubmission) {
  const previous = readPlanSubmission(scope)
  if (previous && JSON.stringify(previous) !== JSON.stringify(submission)) throw new Error('확인하지 못한 조사 접수가 남아 있습니다.')
  sessionStorage.setItem(key(scope), JSON.stringify({ ...scope, submission: { requestId: submission.requestId, bundleId: submission.bundleId,
    provider: submission.provider, requestedModel: submission.requestedModel, digest: submission.digest, approved: true } }))
}

export function removePlanSubmission(scope: Scope, requestId: string) {
  if (readPlanSubmission(scope)?.requestId === requestId) sessionStorage.removeItem(key(scope))
}
