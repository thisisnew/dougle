import assert from 'node:assert/strict'
import { isAbsolute } from 'node:path'

const identifier = value => assert(typeof value === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(value), 'Synthetic identifier required')
const oldArtifact = 'e0e43e1084177ea4d28530547cc762bce339d2de2f5721d21ce70d0ef4eeb58d'

export function validateArtifactBinding(binding) {
  assert(['0.1.25', '0.1.38'].includes(binding.version), 'Explicit supported frozen binding required')
  if (binding.version === '0.1.38') assert.equal(binding.sha256, '97c02f213455511766c475eeb5d934cac50614b7b0d864726b6213a76d36dcbc')
  assert(isAbsolute(binding.jar) && !binding.jar.split('/').includes('..'), 'Absolute immutable artifact required')
  assert(!binding.jar.split('/').includes('target'), 'Mutable build artifact excluded')
  assert.match(binding.sha256, /^[a-f0-9]{64}$/)
  assert.notEqual(binding.sha256, oldArtifact, '.22 is not the approved .25 artifact')
  return { version: binding.version, jar: binding.jar, sha256: binding.sha256 }
}

export function artifactOptions(args) {
  assert.deepEqual(args.filter((value, index) => index % 2 === 0), ['--version', '--jar', '--sha256'], 'Explicit version, absolute JAR and SHA-256 required; no defaults')
  assert.equal(args.length, 6)
  return validateArtifactBinding({ version: args[1], jar: args[3], sha256: args[5] })
}

export function isolationPlan(fixture) {
  const keys = ['ownerWorkspaceId', 'otherWorkspaceId', 'entryId', 'threadId', 'bundleId', 'attachmentId', 'browserJobId', 'answerJobId', 'planJobId']
  for (const key of keys) identifier(fixture[key])
  assert.notEqual(fixture.ownerWorkspaceId, fixture.otherWorkspaceId)
  const path = (workspace, suffix) => `/api/workspaces/${workspace}/${suffix}`
  const endpoints = [
    ['entry', `entries/${fixture.entryId}`], ['entry-content', `entries/${fixture.entryId}/content`],
    ['entry-preview', `entries/${fixture.entryId}/preview`], ['thread', `chat-threads/${fixture.threadId}`],
    ['bundle', `evidence-bundles/${fixture.bundleId}`], ['bundle-validation', `evidence-bundles/${fixture.bundleId}/validation`],
    ['answer-job', `answer-jobs/${fixture.answerJobId}`], ['answer-events', `answer-jobs/${fixture.answerJobId}/events`],
    ['browser-job', `browser-investigations/jobs/${fixture.browserJobId}`],
    ['browser-list', `browser-investigations/jobs?bundleId=${fixture.bundleId}`],
    ['plan-job', `investigation-plans/jobs/${fixture.planJobId}`],
  ]
  return {
    criterion: 'E37', result: 'PLAN_ONLY_NOT_RUN',
    matrix: endpoints.map(([name, suffix]) => ({ name, method: 'GET',
      ownerPath: path(fixture.ownerWorkspaceId, suffix), foreignPath: path(fixture.otherWorkspaceId, suffix),
      ownerStatus: 200, foreignStatus: 404, activeWorkspaceSequence: [fixture.otherWorkspaceId, fixture.ownerWorkspaceId],
      phases: ['before-restart', 'after-restart'], populatedOwnerControlRequired: true })),
    attachmentBoundary: { method: 'POST', path: path(fixture.otherWorkspaceId, 'chat-threads'),
      body: { requestId: 'e37-foreign-attachment', question: 'E37 synthetic ownership check', attachmentIds: [fixture.attachmentId] },
      expectedStatus: 404, noAnswerJobDispatch: true },
    deferred: [
      { scope: 'MYSQL/DATADOG populated result and operational capture', status: 'BLOCKED',
        reason: 'Results/previews are process-memory state; no configured providers or credentials. Missing IDs cannot demonstrate populated-result isolation.' },
      { scope: 'tool proposal payload', status: 'NOT_RUN',
        reason: 'Requires a valid terminal proposal fixture and positive owner response; never generate it with AI for this run.' },
    ],
  }
}

export function validateIsolationObservation(check, observed, foreignCanaries) {
  assert.equal(observed.ownerStatus, check.ownerStatus, 'Positive owner control missing')
  assert.equal(observed.ownerIdentityVerified, true, 'Owner response must contain the exact fixture identity/content')
  assert.equal(observed.foreignStatus, check.foreignStatus, 'Wrong-workspace request was not denied as contracted')
  assert.equal(typeof observed.foreignBody, 'string')
  assert(Array.isArray(foreignCanaries) && foreignCanaries.length > 0)
  for (const canary of foreignCanaries) {
    assert(typeof canary === 'string' && canary.length >= 12, 'Distinct synthetic canary required')
    assert(!observed.foreignBody.includes(canary), 'Foreign content leaked in denial response')
  }
  assert.equal(observed.ownerUnchanged, true, 'Owner data changed')
  assert.equal(observed.aiDispatches, 0, 'AI is not authorized')
  assert.equal(observed.externalRequests, 0, 'External access is not authorized')
}

export function validateUiCheckpoint(ack, binding, now = Date.now()) {
  assert(now < binding.expiresAt, 'Expired UI checkpoint')
  for (const key of ['checkpointId', 'artifactSha256', 'phase', 'ownerWorkspaceId', 'otherWorkspaceId']) assert.equal(ack[key], binding[key])
  assert.equal(ack.reviewer, 'PARENT'); assert.equal(ack.verdict, 'PASS')
  assert.deepEqual(ack.observed, ['ownerHistoryVisible', 'otherHistoryNoForeignCanary', 'returnOwnerHistoryPreserved'])
  assert.match(ack.screenshotPath, /^docs\/screenshots\/e37-[A-Za-z0-9_-]+\.png$/)
}
