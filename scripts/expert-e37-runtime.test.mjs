import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm, writeFile, access, symlink } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { approvedHash, runtimeOptions, snapshotArtifact, terminalSql, verifyHits } from './expert-e37-runtime.mjs'

test('runtime requires exact approved hash, explicit resources and isolated opt-in', () => {
  const args = ['--run-approved-isolated', '--version', '0.1.24', '--source-jar', '/repo/backend/target/app.jar',
    '--sha256', approvedHash, '--java', '/explicit/runtime/bin/java', '--models', '/explicit/models', '--report', '/private/e37.json']
  assert.equal(runtimeOptions(args).sourceJar, '/repo/backend/target/app.jar')
  for (const invalid of [[], args.slice(1), args.slice(0, -2), [...args, '--ui'], args.map(value => value === approvedHash ? 'a'.repeat(64) : value),
    args.map(value => value === approvedHash ? '967cd7e710a8a39c81bd6d13906677f89cc80799424370d659b91feef723d188' : value),
    args.map(value => value === '/private/e37.json' ? '/repo/backend/target/report.json' : value)]) assert.throws(() => runtimeOptions(invalid))
})

test('runtime keeps strict file/network sandbox and has no network-only fallback', async () => {
  const source = await readFile(new URL('./expert-e37-runtime.mjs', import.meta.url), 'utf8')
  assert(source.includes("import { sandboxProfile } from './e50-archive-fixtures.mjs'"))
  assert(source.includes("spawn('/usr/bin/sandbox-exec', ['-p', profile"))
  assert(!source.includes('(allow file-read*'))
  assert(!source.includes("spawn(config.java"))
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
    attachmentId: 'attachment', browserJobId: 'browser', answerJobId: 'answer', planJobId: 'plan', bundleDigest: 'c'.repeat(64), canary: 'E37_PRIVATE_CANARY' }
  const sql = terminalSql(fixture)
  assert.equal((sql.match(/INSERT INTO/g) ?? []).length, 3)
  assert.equal((sql.match(/'SUCCEEDED'/g) ?? []).length, 3)
  assert(!/QUEUED|RUNNING|INSERT INTO answer_approval/.test(sql))
  assert(sql.includes('E37_PRIVATE_CANARY'))
  assert(sql.includes('synthetic-no-dispatch'))
  assert.throws(() => terminalSql({ ...fixture, canary: "x');DROP" }))
  assert.throws(() => terminalSql({ ...fixture, bundleDigest: 'invalid' }))
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
