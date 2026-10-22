import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const temporary = await mkdtemp(resolve(tmpdir(), 'forge-polling-smoke-'))
const fixture = resolve(temporary, 'synthetic')
await mkdir(fixture)
await writeFile(resolve(fixture, 'research.txt'), 'first synthetic revision')
const reservation = createServer().listen(0, '127.0.0.1')
await once(reservation, 'listening')
const port = reservation.address().port
await new Promise(done => reservation.close(done))
const base = `http://127.0.0.1:${port}`
let processHandle
let logs = ''
const delay = milliseconds => new Promise(done => setTimeout(done, milliseconds))
async function api(path, method = 'GET', body) {
  const response = await fetch(`${base}/api${path}`, { method, headers: { 'X-Forge-Client': '1', 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined })
  assert.equal(response.ok, true, `${response.status} ${await response.clone().text()}`)
  return response.json()
}
async function start() {
  processHandle = spawn('bash', ['scripts/run.sh'], { cwd: root, env: { ...process.env, PORT: String(port), FORGE_DATA_DIR: resolve(temporary, 'data'), FORGE_DESKTOP: 'false' }, stdio: ['ignore', 'pipe', 'pipe'] })
  processHandle.stdout.on('data', chunk => { logs += chunk })
  processHandle.stderr.on('data', chunk => { logs += chunk })
  for (let attempt = 0; attempt < 180; attempt++) {
    assert.equal(processHandle.exitCode, null, logs)
    try { await api('/session'); return } catch { await delay(500) }
  }
  throw new Error(`Startup timed out: ${logs}`)
}
async function stop() {
  if (!processHandle || processHandle.exitCode !== null || processHandle.signalCode !== null) return
  const exited = once(processHandle, 'exit')
  processHandle.kill('SIGTERM')
  const timer = setTimeout(() => processHandle.kill('SIGKILL'), 15000)
  await exited
  clearTimeout(timer)
}
try {
  await start()
  const workspace = await api('/workspaces', 'POST', { name: 'Synthetic polling QA' })
  const path = `/workspaces/${workspace.id}/sources`
  const source = await api(path, 'POST', { name: 'Synthetic source', kind: 'LOCAL_FOLDER', location: fixture, domain: 'research' })
  assert.equal(source.pollingEnabled, false)
  const enabled = await api(`${path}/${source.id}/polling`, 'PATCH', { enabled: true, intervalSeconds: 60, expectedRevision: 0 })
  assert.equal(enabled.status, 'NEW')
  console.log(`Polling QA page: ${base}; next due ${new Date(enabled.nextPollAt).toISOString()}`)
  const deadline = Date.now() + 90000
  let current
  do {
    await delay(1000)
    current = (await api(path))[0]
  } while (current.status !== 'READY' && Date.now() < deadline)
  assert.equal(current.status, 'READY', current.error || logs)
  assert.ok(current.revision)
  await api(`${path}/${source.id}/polling`, 'PATCH', { enabled: false, intervalSeconds: 60, expectedRevision: 1 })
  await writeFile(resolve(fixture, 'research.txt'), 'changed synthetic revision while disabled')
  await stop()
  await start()
  const restored = (await api(path))[0]
  assert.equal(restored.pollingEnabled, false)
  assert.equal(restored.pollingRevision, 2)
  assert.equal(restored.nextPollAt, null)
  assert.equal(restored.revision, current.revision)
  assert.equal(restored.status, 'READY')
  console.log('PASS: actual scheduled collection, OFF, immutable prior revision, settings and snapshot after restart; no AI calls')
} finally {
  await stop()
  await rm(temporary, { recursive: true, force: true })
}
