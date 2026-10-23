import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { access, cp, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../', import.meta.url))
const resources = resolve(root, 'desktop/src-tauri/target/release/bundle/macos/Knowledge Forge.app/Contents/Resources/resources')
const jar = process.env.FORGE_BROWSER_JAR ? resolve(process.env.FORGE_BROWSER_JAR) : resolve(resources, 'knowledge-forge.jar')
await access(resolve(resources, 'runtime/bin/java'))
await access(jar)
const temporary = await realpath(await mkdtemp(resolve(tmpdir(), 'forge-browser-e2e-')))
const fixture = resolve(temporary, 'synthetic-project')
const executable = resolve(temporary, 'fake-codex')
const credential = resolve(temporary, 'synthetic-auth')
const calls = resolve(temporary, 'synthetic-inference-count')
await cp(resolve(root, 'fixtures/synthetic-project'), fixture, { recursive: true })
await writeFile(executable, `#!/bin/sh
set -eu
case "$*" in
  '--config cli_auth_credentials_store="file" login status')
    [ "$CODEX_HOME" = '${resolve(temporary, 'data/codex-profile')}' ] || exit 91
    if [ -f "$CODEX_HOME/auth.json" ]; then echo 'Logged in using ChatGPT' >&2; else echo 'Not logged in' >&2; exit 1; fi ;;
  '--config cli_auth_credentials_store="file" login')
    [ "$CODEX_HOME" = '${resolve(temporary, 'data/codex-profile')}' ] || exit 91
    umask 077; printf '{"synthetic":true}' > "$CODEX_HOME/auth.json" ;;
  'login status') if [ -f '${credential}' ]; then echo 'Logged in using ChatGPT' >&2; else echo 'Not logged in' >&2; exit 1; fi ;;
  'login') sleep 0.2; touch '${credential}' ;;
  '--version') exec '${resolve(root, 'scripts/fixtures/fake-codex.sh')}' "$@" ;;
  *) printf 'fixture-call\\n' >> '${calls}'; sleep 3; exec '${resolve(root, 'scripts/fixtures/fake-codex.sh')}' "$@" ;;
esac
`, { mode: 0o700 })
const reservation = createServer().listen(0, '127.0.0.1')
await once(reservation, 'listening')
const port = reservation.address().port
await new Promise(done => reservation.close(done))
const base = `http://127.0.0.1:${port}`
let child
let restarting = false
let closing = false
let logs = ''
async function stop() {
  if (!child?.pid || child.exitCode !== null || child.signalCode !== null) return
  const exited = once(child, 'exit')
  child.kill('SIGTERM')
  const timer = setTimeout(() => child.kill('SIGKILL'), 15000)
  await exited
  clearTimeout(timer)
}
async function start() {
  logs = ''
  child = spawn(resolve(resources, 'runtime/bin/java'), ['-jar', jar,
    '--forge.desktop=false', `--server.port=${port}`, '--server.address=127.0.0.1',
    `--forge.data-dir=${resolve(temporary, 'data')}`, `--forge.ai.codex-command=${executable}`,
    `--forge.ai.claude-command=${resolve(temporary, 'missing-claude')}`, '--forge.ai.codex-model=synthetic-smoke-model',
    ...(process.env.FORGE_BROWSER_PRIVATE_PROFILE === 'true' ? [`--forge.ai.codex-stream-home=${resolve(temporary, 'data/codex-profile')}`] : [])],
  { cwd: temporary, stdio: ['ignore', 'pipe', 'pipe'] })
  let launchError
  child.once('error', error => { launchError = error })
  for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => { logs = (logs + chunk).slice(-8000) })
  for (let attempt = 0; attempt < 180; attempt++) {
    if (launchError) throw launchError
    assert.equal(child.exitCode, null, logs)
    try { if ((await fetch(base + '/api/session', { signal: AbortSignal.timeout(1000) })).ok) return }
    catch {}
    await new Promise(done => setTimeout(done, 500))
  }
  throw new Error('Synthetic browser fixture startup timed out')
}
let finish
const finished = new Promise(done => { finish = done })
const keepAlive = setInterval(() => {}, 1000)
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { closing = true; finish() })
process.on('SIGUSR1', async () => {
  if (closing || restarting) return
  restarting = true
  try { await stop(); if (!closing) { await start(); console.log('RESTART READY:', base) } }
  catch (error) { console.error(error.message); closing = true; finish() }
  finally { restarting = false }
})
process.on('SIGUSR2', async () => {
  if (closing || restarting) return
  restarting = true
  try { await stop(); console.log('PAUSED:', base) }
  catch (error) { console.error(error.message); closing = true; finish() }
  finally { restarting = false }
})
try {
  await start()
  console.log(JSON.stringify({ url: base, pid: process.pid, sourceFolder: fixture, jar,
    safety: 'Isolated data, packaged Java/JAR, browser web mode, fake CLI only; no real AI or user database' }))
  await finished
  try { console.log('Synthetic inference calls:', (await readFile(calls, 'utf8')).trim().split('\n').length) }
  catch { console.log('Synthetic inference calls: 0') }
} finally {
  clearInterval(keepAlive)
  await stop()
  await rm(temporary, { recursive: true, force: true })
}
