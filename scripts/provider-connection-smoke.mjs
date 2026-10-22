import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { mkdtemp, writeFile, rm, access } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../', import.meta.url))
const temporary = await mkdtemp(resolve(tmpdir(), 'forge-provider-'))
const credential = resolve(temporary, 'synthetic-auth')
const forbidden = resolve(temporary, 'unexpected-command')
const executable = resolve(temporary, 'fake-codex')
await writeFile(executable, `#!/bin/sh
case "$*" in
  'login status') if [ -f '${credential}' ]; then echo 'Logged in using ChatGPT' >&2; else echo 'Not logged in' >&2; exit 1; fi ;;
  'login') sleep 0.2; touch '${credential}'; echo 'synthetic-login-output-must-not-leak' ;;
  *) touch '${forbidden}'; exit 99 ;;
esac
`, { mode: 0o700 })
const reservation = createServer().listen(0, '127.0.0.1')
await once(reservation, 'listening')
const port = reservation.address().port
await new Promise(done => reservation.close(done))
const base = `http://127.0.0.1:${port}`
const child = spawn('bash', ['scripts/run.sh', `--forge.ai.codex-command=${executable}`, `--forge.ai.claude-command=${resolve(temporary, 'missing')}`], {
  cwd: root, env: { ...process.env, PORT: String(port), FORGE_DATA_DIR: resolve(temporary, 'data'), FORGE_DESKTOP: 'false' }, stdio: ['ignore', 'pipe', 'pipe'],
})
let logs = ''
for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => { logs = (logs + chunk).slice(-12000) })
async function api(path, method = 'GET', body, expected = 200) {
  const response = await fetch(`${base}/api${path}`, { method, headers: { 'X-Forge-Client': '1', 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined })
  assert.equal(response.status, expected, await response.clone().text())
  const text = await response.text()
  assert.ok(!text.includes('synthetic-login-output-must-not-leak'))
  return JSON.parse(text)
}
try {
  for (let attempt = 0; attempt < 180; attempt++) {
    assert.equal(child.exitCode, null, logs)
    try { await api('/session'); break } catch { await new Promise(done => setTimeout(done, 500)) }
  }
  assert.equal((await api('/ai/providers/CODEX/connection', 'POST')).state, 'LOGIN_REQUIRED')
  await api('/ai/logins', 'POST', { provider: 'CODEX', acknowledged: false }, 400)
  await assert.rejects(access(credential))
  const login = await api('/ai/logins', 'POST', { provider: 'CODEX', acknowledged: true })
  assert.equal(login.state, 'PENDING')
  let state = login
  for (let attempt = 0; attempt < 100 && state.state === 'PENDING'; attempt++) {
    await new Promise(done => setTimeout(done, 100))
    state = await api(`/ai/logins/${login.id}`)
  }
  assert.equal(state.state, 'AUTHENTICATED')
  assert.equal((await api('/ai/providers/CODEX/connection', 'POST')).remoteConnection, 'NOT_CHECKED')
  assert.equal((await api('/ai/providers/CLAUDE/connection', 'POST')).state, 'TOOL_UNAVAILABLE')
  await assert.rejects(access(forbidden))
  assert.ok(!logs.includes('synthetic-login-output-must-not-leak'))
  console.log(`PASS: explicit login consent, fake authentication, status polling, missing provider and no inference/output leakage. ${base}`)
  if (process.argv.includes('--browser')) {
    await rm(credential)
    console.log(`Browser QA ready: ${base}`)
    await new Promise(done => { process.once('SIGINT', done); process.once('SIGTERM', done) })
  }
} finally {
  if (child.exitCode === null && child.signalCode === null) {
    const ended = once(child, 'exit'); child.kill('SIGTERM')
    const timer = setTimeout(() => child.kill('SIGKILL'), 15000)
    await ended; clearTimeout(timer)
  }
  await rm(temporary, { recursive: true, force: true })
}
