import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile, appendFile, mkdir, symlink } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { initializeBudget, reservePersistentCall } from './expert-live-budget.mjs'

const grant = { approvalId: 'synthetic-only', jarSha256: 'a'.repeat(64), limit: 2 }
const call = requestId => ({ kind: 'answer', requestId })
async function fixture(t) {
  const dir = await mkdtemp(join(tmpdir(), 'dougle-budget-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  const path = join(dir, 'ledger')
  await initializeBudget(path, grant)
  return path
}

test('reservation survives a process exit before sending and cannot reset or replay', async t => {
  const path = await fixture(t)
  const script = `import { reservePersistentCall } from ${JSON.stringify(new URL('./expert-live-budget.mjs', import.meta.url).href)};
    await reservePersistentCall(process.argv[1], JSON.parse(process.argv[2]), {kind:'plan',requestId:'first'});`
  const result = spawnSync(process.execPath, ['--input-type=module', '-e', script, path, JSON.stringify(grant)], { encoding: 'utf8' })
  assert.equal(result.status, 0, result.stderr)
  await assert.rejects(initializeBudget(path, grant), { code: 'EEXIST' })
  await assert.rejects(reservePersistentCall(path, grant, call('first')), /Previously reserved/)
  assert.equal(await reservePersistentCall(path, grant, call('second')), 2)
  await assert.rejects(reservePersistentCall(path, grant, call('third')), /exhausted/)
})

test('missing, wrong approval, wrong artifact and changed limit never authorize a call', async t => {
  const path = await fixture(t)
  const before = await readFile(path, 'utf8')
  for (const changed of [{ approvalId: 'other' }, { jarSha256: 'b'.repeat(64) }, { limit: 200 }]) {
    await assert.rejects(reservePersistentCall(path, { ...grant, ...changed }, call('x')), /mismatch/)
  }
  await assert.rejects(reservePersistentCall(path + '-missing', grant, call('x')), { code: 'ENOENT' })
  assert.equal(await readFile(path, 'utf8'), before)
})

test('concurrent attempts never exceed the durable cap', async t => {
  const path = await fixture(t)
  const results = await Promise.allSettled(Array.from({ length: 30 }, (_, i) => reservePersistentCall(path, grant, call('r' + i))))
  const success = results.filter(result => result.status === 'fulfilled')
  assert(success.length > 0 && success.length <= 2)
  assert.equal((await readFile(path, 'utf8')).trim().split('\n').length, success.length + 1)
})

test('partial records and stale locks stop without deleting reservations', async t => {
  const path = await fixture(t)
  await appendFile(path, '{"sequence":1')
  await assert.rejects(reservePersistentCall(path, grant, call('x')), /Incomplete/)
  await mkdir(path + '.lock')
  await assert.rejects(reservePersistentCall(path, grant, call('y')), { code: 'EEXIST' })
  assert((await readFile(path, 'utf8')).endsWith('{"sequence":1'))
})

test('symlink journals cannot redirect reservations', async t => {
  const path = await fixture(t)
  await symlink(path, path + '-link')
  await assert.rejects(reservePersistentCall(path + '-link', grant, call('x')))
  assert.equal((await readFile(path, 'utf8')).trim().split('\n').length, 1)
})
