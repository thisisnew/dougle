import assert from 'node:assert/strict'
import { execFile, spawn } from 'node:child_process'
import { once } from 'node:events'
import { mkdtemp, mkdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { promisify } from 'node:util'

const execute = promisify(execFile)
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const app = resolve(process.argv[2] || resolve(root, 'desktop/src-tauri/target/aarch64-apple-darwin/release/bundle/macos/Dougle.app'))
const home = await mkdtemp(resolve(tmpdir(), 'forge-native-host-'))
const migrateLegacy = process.argv.includes('--legacy-data')
const legacy = resolve(home, 'Library/Application Support/dev.knowledgeforge.desktop')
if (migrateLegacy) {
  await mkdir(legacy, { recursive: true, mode: 0o700 })
  await writeFile(resolve(legacy, 'synthetic-migration-marker'), 'preserve synthetic data')
}
const processes = []
let primaryFailure
const pause = () => new Promise(done => setTimeout(done, 100))

async function table() {
  const { stdout } = await execute('/bin/ps', ['-axo', 'pid,ppid,stat,comm'])
  return stdout.split('\n').map(line => line.match(/^\s*(\d+)\s+(\d+)\s+(\S+)\s+(.+)$/)).filter(Boolean)
    .filter(match => !match[3].startsWith('Z'))
    .map(match => ({ pid: Number(match[1]), parent: Number(match[2]), command: match[4] }))
}

function launch() {
  const native = spawn(resolve(app, 'Contents/MacOS/dougle-desktop'), [], {
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
  const startupOnly = process.argv.includes('--startup-window')
  const observer = resolve(home, 'startup-window-observer')
  if (startupOnly) await execute('/usr/bin/xcrun', ['swiftc', resolve(root, 'scripts/startup-window-observer.swift'), '-o', observer])
  const started = Date.now()
  const first = launch()
  const observedWindows = startupOnly ? execute(observer, [String(first.native.pid)], { timeout: 70000 }) : null
  observedWindows?.catch(() => {})
  const base = await ready(first)
  console.log('PASS: native startup and session protection')
  const startupMs = Date.now() - started
  const data = resolve(home, 'Library/Application Support/dev.dougle.desktop')
  assert.equal((await stat(data)).mode & 0o777, 0o700)
  await stat(resolve(data, 'dougle.sqlite'))
  if (migrateLegacy) {
    assert.equal(await readFile(resolve(data, 'synthetic-migration-marker'), 'utf8'), 'preserve synthetic data')
    await assert.rejects(stat(legacy), { code: 'ENOENT' })
  }
  if (startupOnly) {
    const result = await observedWindows
    console.log(result.stdout.trim())
    if (process.argv.includes('--transient-health')) {
      assert.ok((await table()).some(item => item.pid === first.java && item.parent === first.native.pid))
      process.kill(first.java, 'SIGSTOP')
      try { await new Promise(done => setTimeout(done, 5000)) }
      finally { process.kill(first.java, 'SIGCONT') }
      await new Promise(done => setTimeout(done, 5000))
      assert.equal(first.native.exitCode, null)
      assert.ok((await table()).some(item => item.pid === first.java && item.parent === first.native.pid))
      assert.equal((await fetch(base, { signal: AbortSignal.timeout(5000) })).status, 200)
      assert.equal((await fetch(`${base}/api/session`)).status, 403)
      console.log('PASS: isolated backend 5-second pause recovers without native exit or AI replay')
    }
    await stop(first)
    console.log('PASS: isolated native startup, HTTP 200/403 and child shutdown; no real AI or user data used')
  } else {
    const duplicate = launch()
    const deadline = Date.now() + 65000
    let observed = false
    while (Date.now() < deadline) {
      const child = (await table()).find(item => item.parent === duplicate.native.pid && item.command.endsWith('/runtime/bin/java'))
      if (child) { observed = true; duplicate.java = child.pid }
      if (observed && !(await table()).some(item => item.pid === duplicate.java)) break
      await pause()
    }
    const remaining = (await table()).filter(item => item.pid === duplicate.java || item.parent === duplicate.native.pid)
    assert.ok(observed && remaining.length === 0,
      `Second backend must exit without acquiring data lock: ${JSON.stringify({ observed, remaining, nativeExit: duplicate.native.exitCode, logs: duplicate.logs })}`)
    assert.equal((await fetch(base)).status, 200)
    await stop(duplicate)
    await stop(first)
    if (migrateLegacy) {
      await execute('/usr/bin/sqlite3', [resolve(data, 'dougle.sqlite'), "CREATE TABLE synthetic_migration(value TEXT); INSERT INTO synthetic_migration VALUES('preserved');"])
      await rename(resolve(data, 'dougle.sqlite'), resolve(data, 'knowledge-forge.sqlite'))
      await rename(data, legacy)
    }
    const restarted = launch()
    await ready(restarted)
    await stop(restarted)
    if (migrateLegacy) {
      const database = resolve(data, 'dougle.sqlite')
      const wal = await stat(database + '-wal').catch(error => { if (error.code === 'ENOENT') return { size: 0 }; throw error })
      assert.equal(wal.size, 0, 'Stopped database must be fully checkpointed before immutable verification')
      const uri = pathToFileURL(database); uri.searchParams.set('immutable', '1')
      const result = await execute('/usr/bin/sqlite3', ['-readonly', uri.href, 'SELECT value FROM synthetic_migration;'])
      assert.equal(result.stdout.trim(), 'preserved')
      await assert.rejects(stat(legacy), { code: 'ENOENT' })
      await assert.rejects(stat(resolve(data, 'knowledge-forge.sqlite')), { code: 'ENOENT' })
      console.log('PASS: legacy app directory and stopped SQLite migrate without losing synthetic data')
    }
    const report = { measuredAt: new Date().toISOString(), platform: `${process.platform}/${process.arch}`, startupMs,
      checks: ['packaged native executable with isolated HOME and minimal PATH', 'bundled Java startup and API session protection',
        'private stable application data path', 'duplicate backend rejected while original stays available',
        'parent termination closes Java through stdin EOF', 'native restart uses the same database'],
      limits: 'Synthetic isolated home on the reported native architecture; no Finder/Gatekeeper interaction, company SSO, actual external AI, or Apple notarization.' }
    await writeFile(resolve(root, 'docs/native-host-report.json'), JSON.stringify(report, null, 2) + '\n')
    console.log(JSON.stringify(report, null, 2))
  }
} catch (error) {
  primaryFailure = error
  console.error(error)
  throw error
} finally {
  for (const process of processes.reverse()) {
    try { await stop(process) } catch (error) {
      if (!primaryFailure) throw error
      console.error('Cleanup failure:', error.message)
    }
  }
  if (!primaryFailure) await rm(home, { recursive: true, force: true })
  else console.error('Retained synthetic failure fixture:', home)
}
