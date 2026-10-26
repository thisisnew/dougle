import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm, writeFile, access, symlink } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { approvedHash, proposalContracts, proposalDenialDiagnostics, runtimeOptions, snapshotArtifact, terminalSql, verifyHits, verifyProposal, verifyProposalDenial } from './expert-e37-runtime.mjs'
import { sandboxProfile } from './e50-archive-fixtures.mjs'

test('runtime requires exact approved hash, explicit resources and isolated opt-in', () => {
  const args = ['--run-approved-isolated', '--version', '0.1.25', '--source-jar', '/repo/backend/target/app.jar',
    '--sha256', approvedHash, '--java', '/explicit/runtime/bin/java', '--models', '/explicit/models', '--report', '/private/e37.json']
  assert.equal(runtimeOptions(args).sourceJar, '/repo/backend/target/app.jar')
  for (const invalid of [[], args.slice(1), args.slice(0, -2), [...args, '--ui'], args.map(value => value === approvedHash ? 'a'.repeat(64) : value),
    args.map(value => value === approvedHash ? '967cd7e710a8a39c81bd6d13906677f89cc80799424370d659b91feef723d188' : value),
    args.map(value => value === approvedHash ? 'b5b59534ed02f1ad342a64d55f6e40cff03947997af2c07340b5b9bd85473d47' : value),
    args.map(value => value === '0.1.25' ? '0.1.24' : value),
    args.map(value => value === '/private/e37.json' ? '/repo/backend/target/report.json' : value)]) assert.throws(() => runtimeOptions(invalid))
})

test('runtime keeps strict file/network sandbox and has no network-only fallback', async () => {
  const source = await readFile(new URL('./expert-e37-runtime.mjs', import.meta.url), 'utf8')
  assert(source.includes("import { sandboxProfile } from './e50-archive-fixtures.mjs'"))
  assert(source.includes("spawn('/usr/bin/sandbox-exec', ['-p', profile"))
  assert(!source.includes('(allow file-read*'))
  assert(!source.includes("spawn(config.java"))
  const profile = sandboxProfile('/private/tmp/owned', '/bundle/runtime', '/bundle/models')
  assert(profile.includes('(allow file-read-data (literal "/"))'))
  assert(profile.includes('(deny file-read* file-write*)'))
  assert(!profile.includes('(subpath "/")'))
  assert(profile.includes('(deny network*)'))
})

test('incorrect artifact bytes cannot be copied or launched and source is unchanged', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'e37-guard-'))
  try {
    const source = join(directory, 'source.jar'); const destination = join(directory, 'frozen.jar')
    await writeFile(source, 'synthetic invalid artifact')
    await assert.rejects(snapshotArtifact(source, destination, approvedHash), /pin mismatch/)
    assert.equal(await readFile(source, 'utf8'), 'synthetic invalid artifact')
    await assert.rejects(access(destination))
    const linked = join(directory, 'linked.jar'); await symlink(source, linked)
    await assert.rejects(snapshotArtifact(linked, destination, approvedHash), /Regular source/)
    await assert.rejects(snapshotArtifact(source, join(directory, 'target', 'frozen.jar'), approvedHash), /Mutable build/)
  } finally { await rm(directory, { recursive: true, force: true }) }
})

test('populated jobs are terminal synthetic records and never pending execution', () => {
  const fixture = { ownerWorkspaceId: 'owner', otherWorkspaceId: 'other', entryId: 'entry', threadId: 'thread', bundleId: 'bundle',
    attachmentId: 'attachment', browserJobId: 'browser', answerJobId: 'answer', planJobId: 'plan', bundleDigest: 'c'.repeat(64), canary: 'E37_PRIVATE_CANARY',
    mysqlProposalId: 'mysql-proposal-synthetic', datadogProposalId: 'datadog-proposal-synthetic' }
  const sql = terminalSql(fixture)
  assert.equal((sql.match(/INSERT INTO/g) ?? []).length, 5)
  assert.equal((sql.match(/'SUCCEEDED'/g) ?? []).length, 5)
  assert.equal((sql.match(/'PROPOSE_MYSQL_READ'/g) ?? []).length, 1)
  assert.equal((sql.match(/'PROPOSE_DATADOG_LOGS'/g) ?? []).length, 1)
  assert(!/QUEUED|RUNNING|INSERT INTO answer_approval/.test(sql))
  assert(sql.includes('E37_PRIVATE_CANARY'))
  assert(sql.includes('synthetic-no-dispatch'))
  assert.throws(() => terminalSql({ ...fixture, canary: "x');DROP" }))
  assert.throws(() => terminalSql({ ...fixture, bundleDigest: 'invalid' }))
})

test('persisted proposals require exact scope and fields and cannot authorize execution', () => {
  const fixture = { ownerWorkspaceId: 'owner', bundleId: 'bundle', planJobId: 'search',
    mysqlProposalId: 'mysql-proposal-synthetic', datadogProposalId: 'datadog-proposal-synthetic' }
  const proposals = proposalContracts(fixture)
  assert.deepEqual(proposals.map(value => value.kind), ['MYSQL_READ', 'DATADOG_LOGS'])
  for (const expected of proposals) {
    verifyProposal(structuredClone(expected), expected)
    for (const changes of [{ workspaceId: 'foreign' }, { bundleId: 'foreign' }, { jobId: 'foreign' }, { kind: 'SEARCH' },
      { requiredFields: [] }, { requiredFields: [...expected.requiredFields, 'guessedScope'] }, { executionAllowed: true },
      { executionAllowed: 'false' }, { sql: 'SELECT anything' }]) assert.throws(() => verifyProposal({ ...expected, ...changes }, expected))
  }
  assert.throws(() => proposalContracts({ ...fixture, mysqlProposalId: fixture.datadogProposalId }))
  assert.throws(() => proposalContracts({ ...fixture, planJobId: fixture.mysqlProposalId }))
  assert.throws(() => proposalContracts({ ...fixture, mysqlProposalId: '../invalid' }))
})

test('404 may reflect only the exact supplied path, never private proposal fields in error content', () => {
  const fixture = { ownerWorkspaceId: 'private-owner', bundleId: 'private-bundle', canary: 'E37_PRIVATE_CANARY' }
  const expected = { jobId: 'supplied-job-id' }
  const path = '/api/workspaces/foreign/investigation-plans/jobs/supplied-job-id/tool-proposal'
  const response = { status: 404, body: { status: 404, detail: 'Not found', instance: path } }
  verifyProposalDenial(response, path, fixture, expected)
  assert.deepEqual(proposalDenialDiagnostics(response, path, fixture, expected), {
    instanceMatchesRequest: true, matches: [{ field: 'instance', marker: 'suppliedJobId' }],
  })
  for (const value of Object.values(fixture).concat(expected.jobId)) {
    assert.throws(() => verifyProposalDenial({ ...response, body: { ...response.body, detail: value } }, path, fixture, expected))
  }
  assert.throws(() => verifyProposalDenial({ ...response, status: 200 }, path, fixture, expected))
  assert.throws(() => verifyProposalDenial({ ...response, body: { ...response.body, instance: path + '/extra' } }, path, fixture, expected))
  for (const extension of [{ jobId: expected.jobId }, { payload: { nested: fixture.canary } },
    { extension: path }, { requiredFields: ['table'] }, { executionAllowed: false }, { innocuous: 'unknown' }]) {
    assert.throws(() => verifyProposalDenial({ ...response, body: { ...response.body, ...extension } }, path, fixture, expected), /extension/)
  }
  for (const field of ['type', 'title', 'detail']) {
    assert.throws(() => verifyProposalDenial({ ...response, body: { ...response.body, [field]: path } }, path, fixture, expected))
    assert.throws(() => verifyProposalDenial({ ...response, body: { ...response.body, [field]: { nested: fixture.canary } } }, path, fixture, expected))
  }
  assert.throws(() => verifyProposalDenial({ ...response, body: { ...response.body, status: 200 } }, path, fixture, expected))
  const diagnosis = proposalDenialDiagnostics({ ...response, body: { ...response.body, detail: fixture.canary, arbitrary: fixture.bundleId } }, path, fixture, expected)
  assert(diagnosis.matches.some(value => value.field === 'detail' && value.marker === 'ownerCanary'))
  assert(diagnosis.matches.some(value => value.field === 'EXTENSION_OR_INVALID_BODY' && value.marker === 'ownerBundleId'))
  assert(!JSON.stringify(diagnosis).includes(fixture.canary))
})

test('search acceptance requires populated owner text and rejects foreign hit IDs/text', () => {
  const own = { entryId: 'owned', canary: 'E37_OWNER_CANARY' }
  const foreign = { entryId: 'foreign', canary: 'E37_FOREIGN_CANARY' }
  const hit = { entryId: own.entryId, snippet: own.canary }
  verifyHits([hit], own, foreign)
  for (const hits of [[], [{ ...hit, entryId: foreign.entryId }], [{ ...hit, snippet: 'unrelated' }],
    [{ ...hit, snippet: own.canary + foreign.canary }], [hit, { entryId: foreign.entryId, snippet: foreign.canary }]]) {
    assert.throws(() => verifyHits(hits, own, foreign))
  }
})
