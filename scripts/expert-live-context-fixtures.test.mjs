import assert from 'node:assert/strict'
import test from 'node:test'
import { artifactSha256, knownArtifacts, contextApiAllowed, documents, scenarios, validateContextConfiguration } from './expert-live-context-fixtures.mjs'
import { scheduledDispatches } from './expert-live-schedule.mjs'

test('four purposeful turns fit eight dispatches and restart after correction/followup', () => {
  assert.equal(scheduledDispatches(scenarios, 0, 1, 2), 8)
  assert.equal(scenarios[0].restartBeforeTurn, 3)
  assert(scenarios[0].questions[0][1].includes('BLUE가 아니라 AMBER'))
  assert(scenarios[0].questions[0][2].includes('그 배지'))
  assert(!scenarios[0].questions[0][2].includes('AMBER'))
  assert(Object.values(documents)[0].includes('현재 선택한 대상은 문서가 아니라 대화'))
})

test('context batch refuses unpinned resources, changed budget or protocol instrumentation', () => {
  const environment = { FORGE_EXPERT_JAR_SHA256: artifactSha256, FORGE_EXPERT_READONLY_ARTIFACT_SHA256: artifactSha256,
    FORGE_EXPERT_RESOURCES: '/synthetic/bundle', FORGE_EXPERT_JAR: '/synthetic/app.jar', FORGE_EXPERT_SEARCH_MODELS: '/synthetic/models', FORGE_EXPERT_PROFILE: '/synthetic/profile' }
  validateContextConfiguration(environment, 8, 1, 0)
  for (const change of [{ FORGE_EXPERT_JAR_SHA256: 'old' }, { FORGE_EXPERT_PROFILE: '' }, { FORGE_EXPERT_RESOURCES: '../private' }, { FORGE_EXPERT_PROTOCOL_COUNTER: 'approved' }]) {
    assert.throws(() => validateContextConfiguration({ ...environment, ...change }, 8, 1, 0))
  }
  assert.throws(() => validateContextConfiguration(environment, 10, 1, 0))
})

test('context route allowlist excludes operational providers, external URLs and execution', () => {
  for (const path of ['/workspaces/synthetic/mysql-investigations/preview', '/workspaces/synthetic/datadog-investigations/job/execute',
    '/workspaces/synthetic/browser-investigations/jobs', '/plugins/mysql', '/slack/connection',
    '/workspaces/synthetic/operational-captures/preview', '//evil.invalid/path', '/workspaces/synthetic/investigation-plans/jobs/id/capture']) {
    for (const method of ['GET', 'POST', 'PUT', 'DELETE']) assert.equal(contextApiAllowed(path, method), false)
  }
  assert(contextApiAllowed('/workspaces/synthetic/investigation-plans/jobs?bundleId=bundle', 'GET'))
  assert(contextApiAllowed('/workspaces/synthetic/evidence-bundles/bundle/answer-jobs', 'POST'))
})

test('artifact allowlist accepts only frozen .25/.26 with matching approval and unchanged finite guards', () => {
  assert.deepEqual(Object.values(knownArtifacts), ['0.1.25', '0.1.26'])
  assert(Object.isFrozen(knownArtifacts))
  const environment = { FORGE_EXPERT_RESOURCES: '/synthetic/bundle', FORGE_EXPERT_JAR: '/synthetic/app.jar',
    FORGE_EXPERT_SEARCH_MODELS: '/synthetic/models', FORGE_EXPERT_PROFILE: '/synthetic/profile' }
  for (const pin of Object.keys(knownArtifacts)) {
    const approved = { ...environment, FORGE_EXPERT_JAR_SHA256: pin, FORGE_EXPERT_READONLY_ARTIFACT_SHA256: pin }
    validateContextConfiguration(approved, 8, 1, 0)
    assert.throws(() => validateContextConfiguration({ ...approved, FORGE_EXPERT_READONLY_ARTIFACT_SHA256: 'a'.repeat(64) }, 8, 1, 0))
    assert.throws(() => validateContextConfiguration({ ...approved, FORGE_EXPERT_PROTOCOL_COUNTER: 'approved' }, 8, 1, 0))
    for (const schedule of [[10, 1, 0], [8, 2, 0], [8, 1, 1]]) assert.throws(() => validateContextConfiguration(approved, ...schedule))
  }
  for (const pin of ['a'.repeat(64), 'toString', '__proto__', '', undefined]) {
    assert.throws(() => validateContextConfiguration({ ...environment, FORGE_EXPERT_JAR_SHA256: pin,
      FORGE_EXPERT_READONLY_ARTIFACT_SHA256: pin }, 8, 1, 0))
  }
})
