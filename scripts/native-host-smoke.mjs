import assert from 'node:assert/strict'
import { execFile, spawn } from 'node:child_process'
import { once } from 'node:events'
import { mkdtemp, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'

const execute = promisify(execFile)
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const app = resolve(process.argv[2] || resolve(root, 'desktop/src-tauri/target/release/bundle/macos/Knowledge Forge.app'))
const home = await mkdtemp(resolve(tmpdir(), 'forge-native-host-'))
const processes = []
const pause = () => new Promise(done => setTimeout(done, 100))

async function table() {
  const { stdout } = await execute('/bin/ps', ['-axo', 'pid,ppid,stat,comm'])
  return stdout.split('\n').map(line => line.match(/^\s*(\d+)\s+(\d+)\s+(\S+)\s+(.+)$/)).filter(Boolean)
    .filter(match => !match[3].startsWith('Z'))
    .map(match => ({ pid: Number(match[1]), parent: Number(match[2]), command: match[4] }))
}

function launch() {
  const native = spawn(resolve(app, 'Contents/MacOS/knowledge-forge-desktop'), [], {
    env: { HOME: home, PATH: '/usr/bin:/bin', TMPDIR: tmpdir(), LANG: 'en_US.UTF-8' },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  const process = { native, java: null, logs: '' }
  for (const pipe of [native.stdout, native.stderr]) pipe.on('data', data => { process.logs = (process.logs + data).slice(-4000) })
  native.on('error', error => { process.logs += error.message })
  processes.push(process)
  return process
}

async function ready(process) {
  const deadline = Date.now() + 65000
  while (Date.now() < deadline) {
    assert.equal(process.native.exitCode, null, process.logs)
    const child = (await table()).find(item => item.parent === process.native.pid && item.command === resolve(app, 'Contents/Resources/resources/runtime/bin/java'))
    if (child) {
      process.java = child.pid
      try {
        const { stdout } = await execute('/usr/sbin/lsof', ['-nP', '-a', '-p', String(child.pid), '-iTCP', '-sTCP:LISTEN', '-Fn'])
        const port = stdout.match(/^n127\.0\.0\.1:(\d+)$/m)?.[1]
        if (port) {
          const base = `http://127.0.0.1:${port}`
          if ((await fetch(base, { signal: AbortSignal.timeout(1000) })).ok) {
            assert.equal((await fetch(`${base}/api/session`)).status, 403)
            return base
          }
        }
      } catch (error) { if (error instanceof assert.AssertionError) throw error }
    }
    await pause()
  }
  throw Error(`Native startup timed out: ${process.logs}`)
}

async function stop(process) {
  if (process.native.exitCode === null && process.native.signalCode === null) {
    const ended = once(process.native, 'exit')
    process.native.kill('SIGTERM')
    await ended
  }
  const deadline = Date.now() + 15000
  while (process.java && (await table()).some(item => item.pid === process.java)) {
    assert.ok(Date.now() < deadline, 'Java must exit after native parent termination')
    await pause()
  }
}

try {
  const started = Date.now()
  const first = launch()
  const base = await ready(first)
  const startupMs = Date.now() - started
  const data = resolve(home, 'Library/Application Support/dev.knowledgeforge.desktop')
  assert.equal((await stat(data)).mode & 0o777, 0o700)
  await stat(resolve(data, 'knowledge-forge.sqlite'))
  const duplicate = launch()
  const deadline = Date.now() + 65000
  let observed = false
  while (Date.now() < deadline) {
    const child = (await table()).find(item => item.parent === duplicate.native.pid && item.command.endsWith('/runtime/bin/java'))
    if (child) { observed = true; duplicate.java = child.pid }
    if (observed && !child) break
    await pause()
  }
  const remaining = (await table()).filter(item => item.pid === duplicate.java || item.parent === duplicate.native.pid)
  assert.ok(observed && remaining.length === 0,
    `Second backend must exit without acquiring data lock: ${JSON.stringify({ observed, remaining, nativeExit: duplicate.native.exitCode, logs: duplicate.logs })}`)
  assert.equal((await fetch(base)).status, 200)
  await stop(duplicate)
  await stop(first)
  const restarted = launch()
  await ready(restarted)
  await stop(restarted)
  const report = { measuredAt: new Date().toISOString(), platform: `${process.platform}/${process.arch}`, startupMs,
    checks: ['packaged native executable with isolated HOME and minimal PATH', 'bundled Java startup and API session protection',
      'private stable application data path', 'duplicate backend rejected while original stays available',
      'parent termination closes Java through stdin EOF', 'native restart uses the same database'],
    limits: 'Synthetic isolated home; no Finder/Gatekeeper interaction, actual external AI, or Apple Silicon validation.' }
  await writeFile(resolve(root, 'docs/native-host-report.json'), JSON.stringify(report, null, 2) + '\n')
  console.log(JSON.stringify(report, null, 2))
} finally {
  for (const process of processes.reverse()) await stop(process)
  await rm(home, { recursive: true, force: true })
}
