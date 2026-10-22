import assert from 'node:assert/strict'
import { spawn, execFileSync } from 'node:child_process'
import { once } from 'node:events'
import { mkdtemp, cp, rm } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

assert.equal(process.platform, 'darwin', 'This fixture uses macOS Java discovery and sqlite3.')
assert.ok(process.argv[2], 'Pass the previous v18 release JAR path.')
const previousJar = resolve(process.argv[2])
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const currentJar = resolve(root, 'backend/target/knowledge-forge-0.1.0.jar')
assert.notEqual(previousJar, currentJar)
const temporary = await mkdtemp(resolve(tmpdir(), 'forge-upgrade-smoke-'))
const directory = resolve(temporary, 'data')
const backup = resolve(temporary, 'backup')
const restored = resolve(temporary, 'restored')
const java = resolve(execFileSync('/usr/libexec/java_home', ['-v', '21'], { encoding: 'utf8' }).trim(), 'bin/java')
const reservation = createServer().listen(0, '127.0.0.1')
await once(reservation, 'listening')
const port = reservation.address().port
await new Promise(done => reservation.close(done))
const base = `http://127.0.0.1:${port}`
let child
let logs = ''
async function api(path, method = 'GET', body) {
  const multipart = body instanceof FormData
  const response = await fetch(`${base}/api${path}`, { method, headers: { 'X-Forge-Client': '1', ...(multipart ? {} : { 'Content-Type': 'application/json' }) }, body: multipart ? body : body ? JSON.stringify(body) : undefined })
  assert.ok(response.ok, `${response.status}: ${await response.clone().text()}`)
  return response.json()
}
async function start(jar, data) {
  child = spawn(java, ['-jar', jar, '--forge.desktop=false', '--server.address=127.0.0.1', `--server.port=${port}`, `--forge.data-dir=${data}`], { cwd: root, stdio: ['ignore', 'pipe', 'pipe'] })
  child.stdout.on('data', chunk => { logs += chunk })
  child.stderr.on('data', chunk => { logs += chunk })
  for (let attempt = 0; attempt < 180; attempt++) {
    assert.equal(child.exitCode, null, logs)
    try { await api('/session'); return } catch { await new Promise(done => setTimeout(done, 500)) }
  }
  throw new Error(logs)
}
async function stop() {
  if (!child || child.exitCode !== null || child.signalCode !== null) return
  const exited = once(child, 'exit')
  child.kill('SIGTERM')
  const timer = setTimeout(() => child.kill('SIGKILL'), 15000)
  await exited
  clearTimeout(timer)
}
function schema(data) {
  return Number(execFileSync('/usr/bin/sqlite3', [resolve(data, 'knowledge-forge.sqlite'), 'PRAGMA user_version;'], { encoding: 'utf8' }).trim())
}
try {
  await start(previousJar, directory)
  const workspace = await api('/workspaces', 'POST', { name: 'Synthetic upgrade preservation' })
  const path = `/workspaces/${workspace.id}`
  const content = 'Synthetic upgrade evidence, not company data.'
  const form = new FormData()
  form.append('file', new Blob([content]), 'synthetic.txt')
  const entry = await api(`${path}/files`, 'POST', form)
  const card = await api(`${path}/knowledge`, 'POST', { title: 'Synthetic publication', markdown: 'Synthetic upgrade evidence [E1]', citations: [{ entryId: entry.id, startLine: 1, endLine: 1 }] })
  const cardPath = `${path}/knowledge/${card.card.id}`
  await api(`${cardPath}/review`, 'POST', { expectedVersion: 1, reviewer: 'Synthetic reviewer', note: 'checked fixture', claimsSupported: true, uncertaintyRemoved: true })
  await api(`${cardPath}/publication`, 'POST', { expectedVersion: 1, expectedPublicationRevision: 0 })
  await stop()
  assert.equal(schema(directory), 18)
  await cp(directory, backup, { recursive: true })
  async function verify() {
    assert.equal((await api('/session')).activeWorkspaceId, workspace.id)
    assert.equal((await api(`${cardPath}/versions/1`)).version.markdown, card.version.markdown)
    const response = await fetch(`${base}/api${path}/entries/${entry.id}/content`)
    assert.equal(await response.text(), content)
    assert.equal((await api('/ai/launch-settings')).saved.revision, 0)
  }
  await start(currentJar, directory)
  await verify()
  await stop()
  assert.equal(schema(directory), 21)
  assert.equal(schema(backup), 18)
  await cp(backup, restored, { recursive: true })
  await start(currentJar, restored)
  await verify()
  await stop()
  assert.equal(schema(restored), 21)
  console.log('PASS: real v18 release data -> v21, workspace/bytes/knowledge preserved, stopped backup untouched, restored copy migrates; no AI')
} finally {
  await stop()
  await rm(temporary, { recursive: true, force: true })
}
