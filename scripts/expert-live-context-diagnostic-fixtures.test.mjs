import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { readSeedReport, scenarios, seededAnswerSql, validateDiagnosticConfiguration, verifySeededContext } from './expert-live-context-diagnostic-fixtures.mjs'
import { artifactSha256 } from './expert-live-context-fixtures.mjs'
import { scheduledDispatches } from './expert-live-schedule.mjs'

test('diagnostic schedules exactly plan plus answer and requires wrapper pins', () => {
  assert.equal(scheduledDispatches(scenarios, 0, 1, 2), 2)
  const environment = { FORGE_EXPERT_PROTOCOL_COUNTER: 'approved', FORGE_EXPERT_CODEX_SHA256: '73697f229103f463acaafd8659976caaa4892ec11e2c246c7fa97f93d523f90e',
    FORGE_EXPERT_JAR_SHA256: artifactSha256, FORGE_EXPERT_READONLY_ARTIFACT_SHA256: artifactSha256,
    FORGE_EXPERT_RESOURCES: '/synthetic/bundle', FORGE_EXPERT_JAR: '/synthetic/app.jar', FORGE_EXPERT_SEARCH_MODELS: '/synthetic/models', FORGE_EXPERT_PROFILE: '/synthetic/profile' }
  validateDiagnosticConfiguration(environment, 2, 1, 0)
  assert.throws(() => validateDiagnosticConfiguration(environment, 4, 1, 0))
  assert.throws(() => validateDiagnosticConfiguration({ ...environment, FORGE_EXPERT_PROTOCOL_COUNTER: undefined }, 2, 1, 0))
})

test('seed is pinned exact original answers and fixture SQL cannot launch a job', async () => {
  const bytes = await readFile(new URL('../docs/evaluations/context-live-025-b1-20261026.json', import.meta.url))
  const turns = readSeedReport(bytes)
  assert.equal(turns.length, 2); assert(turns[1].answer.includes('AMBER'))
  assert.throws(() => readSeedReport(Buffer.concat([bytes, Buffer.from(' ')])))
  const sql = seededAnswerSql({ id: 'bundle', workspaceId: 'workspace', digest: 'a'.repeat(64) }, "synthetic 'quoted'", 'job')
  assert(sql.includes("''quoted''")); assert(sql.includes("'SUCCEEDED'")); assert(!sql.includes('answer_approval'))
  assert.throws(() => seededAnswerSql({ id: '../bad', workspaceId: 'workspace', digest: 'a'.repeat(64) }, 'answer', 'job'))
  const bundle = { payload: { chatContext: { history: turns, extractive: { questions: turns, omittedQuestions: 0, omittedAnswers: 0, truncatedAnswerBundleIds: [] } } } }
  verifySeededContext(bundle, turns)
  assert.throws(() => verifySeededContext(bundle, turns.toReversed()))
})
