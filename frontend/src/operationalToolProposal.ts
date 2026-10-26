export type OperationalToolKind = 'MYSQL_READ' | 'DATADOG_LOGS'
export type OperationalToolProposal = {
  jobId: string
  workspaceId: string
  bundleId: string
  kind: OperationalToolKind
  requiredFields: string[]
  executionAllowed: false
}
export type OperationalToolOpenRequest = { requestId: string; proposal: OperationalToolProposal }

export function checkedOperationalProposal(value: unknown): OperationalToolProposal {
  const invalid = () => { throw new Error('운영 조회 제안의 대상과 형식을 확인하지 못했습니다.') }
  if (!value || typeof value !== 'object') return invalid()
  const proposal = value as OperationalToolProposal
  if (Object.keys(proposal).sort().join(',') !== 'bundleId,executionAllowed,jobId,kind,requiredFields,workspaceId'
      || ![proposal.jobId, proposal.workspaceId, proposal.bundleId].every(id => typeof id === 'string' && id.length > 0 && id.length <= 128)
      || proposal.executionAllowed !== false) return invalid()
  const fields = proposal.kind === 'MYSQL_READ' ? ['table', 'columns', 'equalities', 'limit']
    : proposal.kind === 'DATADOG_LOGS' ? ['environment', 'service', 'indexes', 'from', 'to', 'limit'] : null
  if (!fields || !Array.isArray(proposal.requiredFields) || JSON.stringify(proposal.requiredFields) !== JSON.stringify(fields)) return invalid()
  return proposal
}
