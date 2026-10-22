import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { mkdtemp, writeFile, rm, access } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const temporary = await mkdtemp(resolve(tmpdir(), 'forge-cli-settings-'))
const marker = resolve(temporary, 'version-was-run')
const first = resolve(temporary, 'old-cli')
const second = resolve(temporary, 'configured-cli')
await writeFile(first, '#!/bin/sh\nprintf "codex-cli 1.0.0\\n"\n', { mode: 0o700 })
await writeFile(second, `#!/bin/sh\n[ "$1" = '--version' ] || exit 9\ntouch '${marker}'\nprintf 'codex-cli 1.2.3\\n'\n`, { mode: 0o700 })
const reservation = createServer().listen(0, '127.0.0.1')
await once(reservation, 'listening')
const port = reservation.address().port
await new Promise(done => reservation.close(done))
const base = `http://127.0.0.1:${port}`
let child
let logs = ''
async function api(path, method = 'GET', body, expected = 200) {
  const multipart = body instanceof FormData
  const response = await fetch(`${base}/api${path}`, { method, headers: { 'X-Forge-Client': '1', ...(multipart ? {} : { 'Content-Type': 'application/json' }) }, body: multipart ? body : body ? JSON.stringify(body) : undefined })
  assert.equal(response.status, expected, `${response.status} ${await response.clone().text()}`)
  return response.json()
}
async function start() {
  child = spawn('bash', ['scripts/run.sh', `--forge.ai.codex-command=${first}`], { cwd: root, env: { ...process.env, PORT: String(port), FORGE_DATA_DIR: resolve(temporary, 'data'), FORGE_DESKTOP: 'false' }, stdio: ['ignore', 'pipe', 'pipe'] })
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
try {
  await start()
  const initial = await api('/ai/launch-settings')
  assert.equal(initial.activeCodexCommand, first)
  const workspace = await api('/workspaces', 'POST', { name: 'Synthetic CLI configuration' }, 201)
  const path = `/workspaces/${workspace.id}`
  const form = new FormData()
  form.append('file', new Blob(['synthetic observation for configuration binding']), 'synthetic.txt')
  await api(`${path}/files`, 'POST', form, 201)
  const bundle = await api(`${path}/evidence-bundles`, 'POST', { question: 'synthetic observation', domains: [], includeKnowledge: false }, 201)
  const bundlePath = `${path}/evidence-bundles/${bundle.id}`
  const previewRequest = { provider: 'CODEX', requestedModel: '' }
  const before = await api(`${bundlePath}/answer-preview`, 'POST', previewRequest)
  const approval = await api(`${bundlePath}/answer-approval`, 'POST', { ...previewRequest, digest: before.digest, acknowledged: true, expectedApprovalId: null })
  const saved = await api('/ai/launch-settings', 'PUT', { codexPath: second, claudePath: '', expectedRevision: 0, acknowledged: true })
  assert.equal(saved.restartRequired, true)
  assert.equal(saved.activeCodexCommand, first)
  assert.equal((await api(`${bundlePath}/answer-preview`, 'POST', previewRequest)).digest, before.digest)
  await assert.rejects(access(marker))
  await stop()
  await start()
  const restored = await api('/ai/launch-settings')
  assert.equal(restored.activeCodexCommand, second)
  assert.equal(restored.restartRequired, false)
  const after = await api(`${bundlePath}/answer-preview`, 'POST', previewRequest)
  assert.notEqual(after.digest, before.digest)
  await api(`${bundlePath}/answer-jobs`, 'POST', { requestId: crypto.randomUUID(), approvalId: approval.approval.id, digest: before.digest, approved: true }, 409)
  await assert.rejects(access(marker))
  const status = await api('/ai/providers/CODEX/probe', 'POST')
  assert.equal(status.status, 'AVAILABLE')
  assert.equal(status.version, '1.2.3')
  await access(marker)
  console.log(`PASS: persisted CLI path, restart-only activation, changed preview binding, stale approval blocked, explicit fake version probe; no model calls. Browser page: ${base}`)
  if (process.argv.includes('--browser')) {
    console.log(`Synthetic executable for UI QA: ${second}`)
    await new Promise(done => { process.once('SIGINT', done); process.once('SIGTERM', done) })
  }
} finally {
  await stop()
  await rm(temporary, { recursive: true, force: true })
}
