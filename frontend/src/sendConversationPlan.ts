import { ApiError, request } from './api'

export type ConversationPlan = { id: string; workspaceId: string; bundleId: string; provider: string; requestedModel: string | null; digest: string; status: string; decision?: string | null; query?: string | null; output?: string | null; error?: string | null; truncated?: boolean; toolStatus?: string | null }
export type PlanSubmission = { requestId: string; bundleId: string; provider: string; requestedModel: string | null; digest: string; approved: true }

export async function readConversationPlan(workspaceId: string, bundleId: string, signal: AbortSignal): Promise<ConversationPlan | null> {
  signal.throwIfAborted()
  const controller = new AbortController()
  const abort = () => controller.abort()
  signal.addEventListener('abort', abort, { once: true })
  let rejectAbort: () => void = () => {}
  const interrupted = new Promise<never>((_, reject) => {
    rejectAbort = () => reject(new Error('조사 상태를 확인하지 못했습니다. 다시 확인해 주세요.'))
    controller.signal.addEventListener('abort', rejectAbort, { once: true })
  })
  const timer = setTimeout(abort, 10_000)
  let values: ConversationPlan[]
  try {
    values = await Promise.race([request<ConversationPlan[]>(`/workspaces/${encodeURIComponent(workspaceId)}/investigation-plans/jobs?bundleId=${encodeURIComponent(bundleId)}`, { signal: controller.signal }), interrupted])
    controller.signal.throwIfAborted()
  } finally {
    clearTimeout(timer); signal.removeEventListener('abort', abort); controller.signal.removeEventListener('abort', rejectAbort)
  }
  if (!Array.isArray(values) || values.some(value => !value || value.workspaceId !== workspaceId || value.bundleId !== bundleId
    || !value.id || !value.digest || !['CODEX', 'CLAUDE'].includes(value.provider)
    || !['QUEUED', 'RUNNING', 'CANCELLING', 'SUCCEEDED', 'STOPPED', 'FAILED', 'CANCELLED'].includes(value.status)
    || (value.status === 'SUCCEEDED' && !['SEARCH', 'STOP', 'PROPOSE_MYSQL_READ', 'PROPOSE_DATADOG_LOGS'].includes(value.decision ?? '')))) {
    throw new Error('조사 접수 상태를 확인하지 못했습니다.')
  }
  return values.at(-1) ?? null
}

export async function sendConversationPlan(workspaceId: string, bundleId: string, provider: string, model: string | null,
  signal: AbortSignal, remember: (value: PlanSubmission) => void, existing?: PlanSubmission): Promise<ConversationPlan> {
  const base = `/workspaces/${encodeURIComponent(workspaceId)}/investigation-plans`
  const controller = new AbortController()
  const abort = () => controller.abort()
  signal.throwIfAborted()
  signal.addEventListener('abort', abort, { once: true })
  let rejectAbort: () => void = () => {}
  const aborted = new Promise<never>((_, reject) => {
    rejectAbort = () => reject(new Error('조사 접수 상태를 확인하지 못했습니다. 다시 실행하지 않고 상태를 확인해 주세요.'))
    controller.signal.addEventListener('abort', rejectAbort, { once: true })
  })
  const timer = setTimeout(abort, 120_000)
  const checked = (value: ConversationPlan, submission: PlanSubmission) => {
    controller.signal.throwIfAborted()
    if (!value || value.workspaceId !== workspaceId || value.bundleId !== bundleId || value.id !== submission.requestId
      || value.provider !== submission.provider || value.requestedModel !== submission.requestedModel || value.digest !== submission.digest
      || !['QUEUED', 'RUNNING', 'CANCELLING', 'SUCCEEDED', 'STOPPED', 'FAILED', 'CANCELLED'].includes(value.status)) throw new Error('조사 대상 또는 상태가 다릅니다.')
    if (value.status === 'SUCCEEDED' && !['SEARCH', 'STOP', 'PROPOSE_MYSQL_READ', 'PROPOSE_DATADOG_LOGS'].includes(value.decision ?? '')) {
      throw new Error('조사 결과를 확인하지 못했습니다.')
    }
    return value
  }
  async function run() {
    let submission = existing
    if (submission && (submission.bundleId !== bundleId || submission.provider !== provider || (model !== null && submission.requestedModel !== model))) {
      throw new Error('이전 제공자와 모델로 돌아가 조사 접수 상태를 확인해 주세요.')
    }
    if (!submission) {
      const preview = await request<{ bundleId: string; provider: string; requestedModel: string | null; digest: string }>(`${base}/preview`, {
        method: 'POST', signal: controller.signal, body: JSON.stringify({ bundleId, provider, requestedModel: model }),
      })
      controller.signal.throwIfAborted()
      if (preview.bundleId !== bundleId || preview.provider !== provider || (model !== null && preview.requestedModel !== model) || !preview.digest) {
        throw new Error('조사 조건이 변경되었습니다. 다시 확인해 주세요.')
      }
      submission = { requestId: crypto.randomUUID(), bundleId, provider, requestedModel: preview.requestedModel, digest: preview.digest, approved: true }
      remember(submission)
    }
    let reconciled: ConversationPlan | undefined
    if (existing) {
      try { reconciled = checked(await request<ConversationPlan>(`${base}/jobs/${encodeURIComponent(submission.requestId)}`, { signal: controller.signal }), submission) }
      catch (reason) { if (!(reason instanceof ApiError) || reason.status !== 404) throw reason }
    }
    controller.signal.throwIfAborted()
    let job = reconciled ?? checked(await request<ConversationPlan>(`${base}/jobs`, { method: 'POST', signal: controller.signal, body: JSON.stringify(submission) }), submission)
    while (['QUEUED', 'RUNNING', 'CANCELLING'].includes(job.status)) {
      await new Promise<void>(resolve => setTimeout(resolve, 1000))
      controller.signal.throwIfAborted()
      const value = checked(await request<ConversationPlan>(`${base}/jobs/${encodeURIComponent(job.id)}`, { signal: controller.signal }), submission)
      if (value.id !== job.id) throw new Error('조사 접수 대상이 다릅니다.')
      job = value
    }
    return job
  }
  try { return await Promise.race([run(), aborted]) }
  finally {
    clearTimeout(timer); signal.removeEventListener('abort', abort); controller.signal.removeEventListener('abort', rejectAbort)
  }
}
