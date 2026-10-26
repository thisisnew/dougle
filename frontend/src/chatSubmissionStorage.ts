import type { ChatSubmission } from './sendChatAnswer'

export type ChatSubmissionScope = { workspaceId: string; bundleId: string; bundleDigest: string }
export type SavedChatSubmission = ChatSubmissionScope & {
  provider: string; requestedModel: string | null; submission: ChatSubmission
}

function storageKey(scope: ChatSubmissionScope) {
  return `dougle.chat-submission.${encodeURIComponent(scope.workspaceId)}.${encodeURIComponent(scope.bundleId)}`
}

export function readChatSubmission(scope: ChatSubmissionScope): SavedChatSubmission | null {
  try {
    const raw = sessionStorage.getItem(storageKey(scope))
    if (raw === null) return null
    const value = JSON.parse(raw) as SavedChatSubmission
    if (!value || value.workspaceId !== scope.workspaceId || value.bundleId !== scope.bundleId || value.bundleDigest !== scope.bundleDigest
      || !['CODEX', 'CLAUDE'].includes(value.provider) || (value.requestedModel !== null && typeof value.requestedModel !== 'string')
      || !value.submission || value.submission.approved !== true
      || ![value.submission.requestId, value.submission.approvalId, value.submission.digest].every(item => typeof item === 'string' && item.length > 0)) throw new Error()
    return { workspaceId: scope.workspaceId, bundleId: scope.bundleId, bundleDigest: scope.bundleDigest, provider: value.provider, requestedModel: value.requestedModel,
      submission: { requestId: value.submission.requestId, approvalId: value.submission.approvalId, digest: value.submission.digest, approved: true } }
  } catch {
    throw new Error('저장된 답변 접수 정보를 확인하지 못했습니다. 이 화면에서는 다시 전송하지 않습니다.')
  }
}

export function saveChatSubmission(value: SavedChatSubmission) {
  const { workspaceId, bundleId, bundleDigest, provider, requestedModel, submission } = value
  const record = { workspaceId, bundleId, bundleDigest, provider, requestedModel,
    submission: { requestId: submission.requestId, approvalId: submission.approvalId, digest: submission.digest, approved: true } }
  const previous = readChatSubmission(value)
  if (previous && JSON.stringify(previous) !== JSON.stringify(record)) throw new Error('확인하지 못한 답변 접수가 남아 있습니다.')
  try {
    sessionStorage.setItem(storageKey(value), JSON.stringify(record))
  } catch {
    throw new Error('답변 접수 정보를 보관하지 못해 전송하지 않았습니다. 브라우저 저장 공간을 확인해 주세요.')
  }
}

export function removeChatSubmission(value: SavedChatSubmission) {
  const current = readChatSubmission(value)
  if (current && current.submission.requestId === value.submission.requestId
    && current.submission.approvalId === value.submission.approvalId && current.submission.digest === value.submission.digest) {
    sessionStorage.removeItem(storageKey(value))
  }
}
