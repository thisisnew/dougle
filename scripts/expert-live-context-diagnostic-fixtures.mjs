import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { documents, scenarios as baseline, validateContextConfiguration } from './expert-live-context-fixtures.mjs'

export { documents }
export const seedReportSha256 = 'a0b724ec0ed3562c9ac35db12201f1f9655f35b35cdc072bf3a1155b505ab0d9'
export const scenarios = [{ id: 'seeded-exact-implicit-followup', questions: [[baseline[0].questions[0][2]]],
  expected: [baseline[0].expected[2]], forbidden: [baseline[0].forbidden[2]], expectedDecisions: ['STOP'],
  manualCriteria: 'Seeded exact b1 prior questions/answers; not uninterrupted third turn. Resolve that badge to corrected AMBER, no auto-renewal, visitor-desk manual approval grounded in R1. No invented actual issuance/approval. Protocol diagnostic, not E04/E05 acceptance.',
}]

export function validateDiagnosticConfiguration(environment, cap, variants, start) {
  assert.equal(cap, 2); assert.equal(environment.FORGE_EXPERT_PROTOCOL_COUNTER, 'approved')
  validateContextConfiguration({ ...environment, FORGE_EXPERT_PROTOCOL_COUNTER: undefined }, 8, variants, start)
  assert.equal(environment.FORGE_EXPERT_CODEX_SHA256, '73697f229103f463acaafd8659976caaa4892ec11e2c246c7fa97f93d523f90e')
}

export function readSeedReport(bytes) {
  assert.equal(createHash('sha256').update(bytes).digest('hex'), seedReportSha256, 'Exact preserved b1 evidence required')
  const report = JSON.parse(bytes.toString('utf8'))
  const turns = report.results.slice(0, 2)
  for (const [index, turn] of turns.entries()) {
    assert.equal(turn.question, baseline[0].questions[0][index])
    assert.equal(turn.status, 'SUCCEEDED'); assert.equal(turn.manualVerdict, 'PASS')
    assert.equal(typeof turn.answer, 'string'); assert(turn.answer.length > 0)
  }
  assert.equal(turns.length, 2)
  return turns.map(({ question, answer }) => ({ question, answer }))
}

export function seededAnswerSql(bundle, answer, id) {
  for (const value of [bundle.id, bundle.workspaceId, id]) assert.match(value, /^[A-Za-z0-9_-]{1,128}$/)
  assert.match(bundle.digest, /^[a-f0-9]{64}$/)
  assert(typeof answer === 'string' && answer.length > 0 && answer.length <= 4000)
  const quote = value => `'${value.replaceAll("'", "''")}'`
  const now = '2026-10-26T00:00:00Z'
  const values = [id, bundle.workspaceId, bundle.id, 'seeded-' + id, 'CODEX', 'synthetic-seeded-b1-not-generated', bundle.digest,
    'SUCCEEDED', answer, null, now, now]
  return 'PRAGMA foreign_keys=ON; INSERT INTO answer_job VALUES(' + values.map(value => value === null ? 'NULL' : quote(value)).join(',') + ');'
}

export function verifySeededContext(bundle, turns) {
  const context = bundle.payload.chatContext
  assert.deepEqual(context.history.map(({ question, answer }) => ({ question, answer })), turns)
  assert.equal(context.extractive.omittedQuestions, 0); assert.equal(context.extractive.omittedAnswers, 0)
  assert.deepEqual(context.extractive.truncatedAnswerBundleIds, [])
  assert.deepEqual(context.extractive.questions.map(value => value.question), turns.map(value => value.question))
}
