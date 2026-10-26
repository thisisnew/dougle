import assert from 'node:assert/strict'
import test from 'node:test'
import { assertReadOnly, childEnvironment, denialCases, fixtureVersion, legacyReceipts, obsoleteJar, options, sandboxProfile, seedSql, startupDiagnostic } from './readonly-runtime-smoke.mjs'

const valid = ['--run-approved-artifact', '--jar', '/synthetic/new.jar', '--sha256', '1'.repeat(64), '--java', '/synthetic/java']

test('desktop bootstrap receives the private absolute data directory without ambient credentials or JVM options', () => {
  assert.deepEqual(childEnvironment('/synthetic/home', '/synthetic/data'), {
    HOME: '/synthetic/home', FORGE_DATA_DIR: '/synthetic/data', PATH: '/usr/bin:/bin:/usr/sbin:/sbin', LANG: 'en_US.UTF-8',
  })
  assert.throws(() => childEnvironment('/synthetic/home', 'relative'))
})

test('startup diagnostics retain bounded platform errors without session tokens or unrelated output', () => {
  const diagnostic = startupDiagnostic('unrelated synthetic data\nsandbox-exec: sandbox_apply: Operation not permitted\nError: session123 Bearer abc xoxb-synthetic-secret', 'session123')
  assert(diagnostic.includes('sandbox_apply: Operation not permitted'))
  assert(!diagnostic.includes('session123'))
  assert(!diagnostic.includes('unrelated synthetic data'))
  assert(!diagnostic.includes('xoxb-synthetic-secret'))
  assert(!diagnostic.includes('Bearer abc'))
  assert(startupDiagnostic(('Error: ' + 'x'.repeat(1000) + '\n').repeat(30), 'session').length <= 3207)
})

test('explicit approval and exact new artifact hash are mandatory', () => {
  assert.deepEqual(options(valid), { jar: '/synthetic/new.jar', hash: '1'.repeat(64), java: '/synthetic/java' })
  for (const invalid of [[], valid.slice(1), [...valid, '--extra'], valid.map(value => value === '1'.repeat(64) ? obsoleteJar : value),
    valid.map(value => value === '1'.repeat(64) ? 'short' : value), valid.map(value => value === '/synthetic/new.jar' ? 'relative.jar' : value)]) {
    assert.throws(() => options(invalid))
  }
})

test('all three write routes receive old approval, malformed, absent and wrong-typed bodies', () => {
  const cases = denialCases('synthetic-workspace', 'synthetic-receipt', 'synthetic-repeated-request', 'a'.repeat(64))
  assert.equal(cases.length, 15)
  const paths = [...new Set(cases.map(candidate => candidate.path))]
  assert.equal(paths.length, 3)
  for (const path of paths) {
    const bodies = cases.filter(candidate => candidate.path === path).map(candidate => candidate.body)
    assert(bodies.includes(undefined)); assert(bodies.includes('{invalid-json'))
    assert(bodies.some(body => body?.includes('"approved":true') && body.includes('a'.repeat(64))))
  }
  assert.deepEqual(cases, denialCases('synthetic-workspace', 'synthetic-receipt', 'synthetic-repeated-request', 'a'.repeat(64)))
  assert.throws(() => denialCases('../unsafe', 'receipt', 'request', 'a'.repeat(64)))
})

test('generic forbidden or malformed-body errors cannot be counted as READ_ONLY evidence', () => {
  assertReadOnly(403, { detail: 'READ_ONLY: synthetic policy denial' })
  for (const [status, body] of [[400, { detail: 'READ_ONLY:' }], [404, {}], [403, { detail: 'Forbidden' }], [200, {}]]) {
    assert.throws(() => assertReadOnly(status, body))
  }
})

test('legacy fixtures include terminal and unconsumed approval rows, not actual Slack sends', () => {
  const rows = legacyReceipts('synthetic-workspace')
  assert.deepEqual(rows.map(row => row.status), ['PREVIEW', 'SENT', 'UNCERTAIN'])
  assert.deepEqual(rows.map(row => row.dispatch_count), [0, 1, 1])
  assert(rows.every(row => row.body.includes('no Slack operation was performed')))
  assert(rows.every(row => JSON.parse(row.scope_json).workspaceId === 'synthetic-workspace'))
  assert.equal(fixtureVersion, 'readonly-slack-receipts-v1')
  assert.throws(() => legacyReceipts("bad';--"))
})

test('seed SQL is transactional, scoped to synthetic reply rows and escapes literals', () => {
  const rows = legacyReceipts('synthetic-workspace')
  rows[0].body = "Synthetic owner's receipt"
  const sql = seedSql(rows)
  assert(sql.startsWith('PRAGMA foreign_keys=ON; BEGIN IMMEDIATE;'))
  assert(sql.endsWith('COMMIT;'))
  assert.equal((sql.match(/INSERT INTO slack_reply /g) ?? []).length, 3)
  assert(sql.includes("Synthetic owner''s receipt"))
  assert.doesNotMatch(sql, /DELETE |UPDATE |DROP |ATTACH /)
})

test('sandbox contract allows only loopback network, with no unsandboxed fallback', () => {
  assert(sandboxProfile.includes('(deny network*)'))
  assert(sandboxProfile.includes('(allow network-bind (local ip "localhost:*"))'))
  assert(sandboxProfile.includes('(local ip "localhost:*")'))
  assert(sandboxProfile.includes('(remote ip "localhost:*")'))
  assert(!sandboxProfile.includes('remote ip "*:'))
})
