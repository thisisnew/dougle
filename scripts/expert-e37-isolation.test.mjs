import assert from 'node:assert/strict'
import test from 'node:test'
import { artifactOptions, isolationPlan, validateArtifactBinding, validateIsolationObservation, validateUiCheckpoint } from './expert-e37-isolation.mjs'

const fixture = { ownerWorkspaceId: 'owner', otherWorkspaceId: 'other', entryId: 'entry', threadId: 'thread',
  bundleId: 'bundle', attachmentId: 'attachment', browserJobId: 'browser', answerJobId: 'answer', planJobId: 'plan' }

test('artifact binding rejects implicit old or mutable build artifacts', () => {
  const binding = { version: '0.1.25', jar: '/synthetic/immutable/resources/knowledge-forge.jar', sha256: 'a'.repeat(64) }
  assert.deepEqual(validateArtifactBinding(binding), binding)
  for (const change of [{ version: '0.1.22' }, { version: '0.1.23' }, { jar: 'relative.jar' }, { jar: '/repo/backend/target/app.jar' }, { jar: '/repo/desktop/target/app.jar' },
    { jar: '/safe/../unsafe.jar' }, { sha256: 'invalid' },
    { sha256: 'e0e43e1084177ea4d28530547cc762bce339d2de2f5721d21ce70d0ef4eeb58d' }]) {
    assert.throws(() => validateArtifactBinding({ ...binding, ...change }))
  }
})

test('explicit artifact options have no installed-app or target fallback', () => {
  const args = ['--version', '0.1.25', '--jar', '/synthetic/frozen/app.jar', '--sha256', 'b'.repeat(64)]
  assert.equal(artifactOptions(args).jar, '/synthetic/frozen/app.jar')
  for (const invalid of [[], args.slice(0, 4), [...args, '--run'], ['--jar', args[3], '--version', '0.1.25', '--sha256', args[5]],
    [...args.slice(0, 5), ''], [...args.slice(0, 2), '--jar', '/backend/target/app.jar', ...args.slice(4)]]) {
    assert.throws(() => artifactOptions(invalid))
  }
})

test('each cross-workspace probe requires owner control with both active workspace selections and restart', () => {
  const plan = isolationPlan(fixture)
  assert.equal(plan.result, 'PLAN_ONLY_NOT_RUN')
  assert.equal(plan.matrix.length, 11)
  for (const check of plan.matrix) {
    assert.equal(check.method, 'GET')
    assert(check.ownerPath.startsWith('/api/workspaces/owner/'))
    assert(check.foreignPath.startsWith('/api/workspaces/other/'))
    assert.deepEqual(check.activeWorkspaceSequence, ['other', 'owner'])
    assert.deepEqual(check.phases, ['before-restart', 'after-restart'])
    assert.equal(check.populatedOwnerControlRequired, true)
  }
  assert.equal(plan.attachmentBoundary.body.attachmentIds[0], 'attachment')
  assert(plan.deferred.every(check => check.status !== 'PASS'))
})

test('same workspace or injected identifiers cannot form an isolation probe', () => {
  assert.throws(() => isolationPlan({ ...fixture, otherWorkspaceId: 'owner' }))
  for (const value of ['', '../other', 'id?workspace=other', 'id/child', null]) {
    assert.throws(() => isolationPlan({ ...fixture, bundleId: value }))
  }
})

test('missing owner, leaked error content, unexpected status, mutation or external activity fails observation', () => {
  const check = isolationPlan(fixture).matrix[0]
  const observed = { ownerStatus: 200, ownerIdentityVerified: true, foreignStatus: 404, foreignBody: '{"detail":"not found"}',
    ownerUnchanged: true, aiDispatches: 0, externalRequests: 0 }
  validateIsolationObservation(check, observed, ['E37_PRIVATE_CANARY'])
  for (const change of [{ ownerStatus: 404 }, { ownerIdentityVerified: false }, { foreignStatus: 200 }, { foreignStatus: 500 },
    { foreignBody: 'E37_PRIVATE_CANARY' }, { ownerUnchanged: false }, { aiDispatches: 1 }, { externalRequests: 1 }]) {
    assert.throws(() => validateIsolationObservation(check, { ...observed, ...change }, ['E37_PRIVATE_CANARY']))
  }
  assert.throws(() => validateIsolationObservation(check, observed, []))
})

test('UI review is independent, bound to artifact/workspaces/phase, and never automatic', () => {
  const binding = { checkpointId: 'checkpoint', artifactSha256: 'b'.repeat(64), phase: 'restart',
    ownerWorkspaceId: 'owner', otherWorkspaceId: 'other', expiresAt: 100 }
  const ack = { ...binding, reviewer: 'PARENT', verdict: 'PASS', screenshotPath: 'docs/screenshots/e37-restart.png',
    observed: ['ownerHistoryVisible', 'otherHistoryNoForeignCanary', 'returnOwnerHistoryPreserved'] }
  validateUiCheckpoint(ack, binding, 99)
  for (const change of [{ reviewer: 'AUTO' }, { verdict: 'FAIL' }, { otherWorkspaceId: 'owner' }, { phase: 'initial' },
    { observed: [] }, { screenshotPath: '/tmp/private.png' }]) assert.throws(() => validateUiCheckpoint({ ...ack, ...change }, binding, 99))
  assert.throws(() => validateUiCheckpoint(ack, binding, 100))
})
