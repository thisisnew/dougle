import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'node:http'
import { mkdtemp, readFile, writeFile, stat, rm, symlink } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { createReadOnlyBridge, holdAtRestart, validateAcknowledgment } from './expert-live-ui-hold.mjs'

const binding = { artifactSha256: 'b'.repeat(64), workspaceId: 'workspace', threadId: 'thread', variant: 0,
  bundleIds: ['bundle'], bundleDigests: ['digest'] }
const acknowledgment = checkpoint => Object.fromEntries([...Object.keys(binding), 'checkpointId'].map(key => [key, checkpoint[key]]))
function ack(checkpoint) {
  return { ...acknowledgment(checkpoint), observed: 'HISTORY_REOPENED_NO_SUBMIT', screenshotPath: 'docs/screenshots/synthetic-hold.png' }
}
async function fixture(run) {
  const directory = await mkdtemp(join(tmpdir(), 'expert-ui-hold-test-'))
  const requests = []
  const server = createServer((request, response) => {
    requests.push({ method: request.method, path: request.url })
    assert.equal(request.headers['x-forge-session'], 'a'.repeat(64))
    assert.equal(request.headers['x-forge-client'], '1')
    response.setHeader('Content-Type', 'application/json')
    response.end(JSON.stringify({ synthetic: true }))
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const options = { ownedDirectory: directory, binding, backend: `http://127.0.0.1:${server.address().port}`,
    session: 'a'.repeat(64), before: { jobs: ['completed'] }, snapshot: async () => ({ jobs: ['completed'] }), timeoutMs: 3000, pollMs: 5 }
  try { await run({ options, requests, directory }) }
  finally {
    server.closeAllConnections()
    await new Promise(resolve => server.close(resolve))
    await rm(directory, { recursive: true, force: true })
  }
}
async function visit(origin) {
  assert.equal((await fetch(origin + '/api/workspaces/workspace/chat-threads')).status, 200)
  assert.equal((await fetch(origin + '/api/workspaces/workspace/chat-threads/thread')).status, 200)
}

test('exact acknowledgment rejects stale binding, missing observations, unknown fields and expiry', () => {
  const checkpoint = { ...binding, checkpointId: 'nonce', expiresAt: 100 }
  validateAcknowledgment(ack(checkpoint), checkpoint, 99)
  for (const change of [{ checkpointId: 'stale' }, { artifactSha256: 'other' }, { variant: 1 }, { workspaceId: 'other' },
    { threadId: 'other' }, { bundleDigests: ['changed'] }, { bundleIds: [] }, { observed: 'SUBMITTED' },
    { token: 'forbidden' }, { screenshotPath: '../private.png' }]) {
    assert.throws(() => validateAcknowledgment({ ...ack(checkpoint), ...change }, checkpoint, 99))
  }
  assert.throws(() => validateAcknowledgment(ack(checkpoint), checkpoint, 100), /expired/)
})

test('private hold accepts exact UI GETs and unchanged state, closes bridge and deletes control files', async () => fixture(async ({ options, requests }) => {
  let control
  let origin
  const result = await holdAtRestart({ ...options, notify: async details => {
    control = details.controlPath
    origin = details.uiUrl
    assert.equal((await stat(dirname(control))).mode & 0o777, 0o700)
    assert.equal((await stat(control)).mode & 0o777, 0o600)
    const text = await readFile(control, 'utf8')
    assert.ok(!text.includes(options.session))
    const checkpoint = JSON.parse(text)
    await visit(origin)
    await writeFile(checkpoint.acknowledgmentPath, JSON.stringify(ack(checkpoint)), { mode: 0o600 })
  } })
  assert.equal(result.uiReopenObserved, true)
  assert.equal(requests.length, 2)
  assert.ok(requests.every(request => request.method === 'GET'))
  assert.ok(!JSON.stringify(result).includes(options.session))
  await assert.rejects(stat(dirname(control)), { code: 'ENOENT' })
  await assert.rejects(fetch(origin))
}))

test('browser POST is never forwarded and causes hold failure even with later valid acknowledgment', async () => fixture(async ({ options, requests }) => {
  await assert.rejects(holdAtRestart({ ...options, notify: async details => {
    const checkpoint = JSON.parse(await readFile(details.controlPath, 'utf8'))
    assert.equal((await fetch(details.uiUrl + '/api/workspaces/workspace/chat-threads', { method: 'POST', body: '{}' })).status, 405)
    await visit(details.uiUrl)
    await writeFile(checkpoint.acknowledgmentPath, JSON.stringify(ack(checkpoint)), { mode: 0o600 })
  } }), /attempted submission/)
  assert.ok(requests.every(request => request.method === 'GET'))
}))

test('bridge rejects foreign origin, cross-workspace and operational paths without forwarding', async () => fixture(async ({ options, requests }) => {
  const bridge = await createReadOnlyBridge({ ...options, ...binding })
  try {
    for (const path of ['/api/workspaces/other/chat-threads', '/api/slack/workspaces/workspace/replies', '/api/workspaces/workspace/mysql-investigations']) {
      assert.equal((await fetch(bridge.origin + path)).status, 403)
    }
    assert.equal((await fetch(bridge.origin + '/api/session', { headers: { Origin: 'https://untrusted.invalid' } })).status, 403)
    assert.equal(requests.length, 0)
  } finally { await bridge.close() }
}))

test('UI mount connection check is explicitly not performed; scoped initial entries GET and reopen still work', async () => fixture(async ({ options, requests }) => {
  const result = await holdAtRestart({ ...options, notify: async details => {
    const checkpoint = JSON.parse(await readFile(details.controlPath, 'utf8'))
    for (const provider of ['CODEX', 'CLAUDE']) {
      assert.equal((await fetch(`${details.uiUrl}/api/ai/providers/${provider}/connection`, { method: 'POST' })).status, 409)
    }
    assert.equal((await fetch(details.uiUrl + '/api/workspaces/workspace/entries?area=ORIGINAL')).status, 200)
    for (const path of ['/api/workspaces/other/entries?area=ORIGINAL', '/api/workspaces/workspace/entries?area=KNOWLEDGE',
      '/api/workspaces/workspace/entries?area=ORIGINAL&parentId=other', '/api/session?token=forbidden']) {
      assert.equal((await fetch(details.uiUrl + path)).status, 403)
    }
    await visit(details.uiUrl)
    await writeFile(checkpoint.acknowledgmentPath, JSON.stringify(ack(checkpoint)), { mode: 0o600 })
  } })
  assert.equal(result.uiReopenObserved, true)
  assert.equal(result.trace.filter(entry => entry.action === 'CONNECTION_CHECK_NOT_PERFORMED').length, 2)
  assert.deepEqual(requests, [
    { method: 'GET', path: '/api/workspaces/workspace/entries?area=ORIGINAL' },
    { method: 'GET', path: '/api/workspaces/workspace/chat-threads' },
    { method: 'GET', path: '/api/workspaces/workspace/chat-threads/thread' },
  ])
}))

test('expiry, stale ack and state drift clean owned control directory and never resume', async () => fixture(async ({ options }) => {
  for (const mode of ['expiry', 'stale', 'state']) {
    let control
    await assert.rejects(holdAtRestart({ ...options, timeoutMs: mode === 'expiry' ? 50 : 3000,
      snapshot: async () => mode === 'state' ? { jobs: ['unexpected'] } : options.before,
      notify: async details => {
        control = details.controlPath
        if (mode === 'expiry') return
        const checkpoint = JSON.parse(await readFile(control, 'utf8'))
        await visit(details.uiUrl)
        await writeFile(checkpoint.acknowledgmentPath, JSON.stringify({ ...ack(checkpoint),
          checkpointId: mode === 'stale' ? 'old-checkpoint' : checkpoint.checkpointId }), { mode: 0o600 })
      } }))
    await assert.rejects(stat(dirname(control)), { code: 'ENOENT' })
  }
}))

test('abort, notifier error and symlink acknowledgment fail closed with cleanup', async () => fixture(async ({ options, directory }) => {
  for (const mode of ['abort', 'notify-error', 'symlink']) {
    let control
    const abort = new AbortController()
    await assert.rejects(holdAtRestart({ ...options, signal: abort.signal, notify: async details => {
      control = details.controlPath
      if (mode === 'abort') { abort.abort(); return }
      if (mode === 'notify-error') throw Error('Synthetic parent notification error')
      const checkpoint = JSON.parse(await readFile(control, 'utf8'))
      const target = join(directory, 'synthetic-ack.json')
      await writeFile(target, JSON.stringify(ack(checkpoint)), { mode: 0o600 })
      await symlink(target, checkpoint.acknowledgmentPath)
    } }))
    await assert.rejects(stat(dirname(control)), { code: 'ENOENT' })
  }
}))

test('hung parent notifier or snapshot cannot extend fixed hold deadline', async () => fixture(async ({ options }) => {
  for (const mode of ['notify', 'snapshot']) {
    let control
    await assert.rejects(holdAtRestart({ ...options, timeoutMs: 100,
      snapshot: () => new Promise(() => {}), notify: async details => {
        control = details.controlPath
        if (mode === 'notify') return new Promise(() => {})
        const checkpoint = JSON.parse(await readFile(control, 'utf8'))
        await visit(details.uiUrl)
        await writeFile(checkpoint.acknowledgmentPath, JSON.stringify(ack(checkpoint)), { mode: 0o600 })
      } }), /expired/)
    await assert.rejects(stat(dirname(control)), { code: 'ENOENT' })
  }
}))
